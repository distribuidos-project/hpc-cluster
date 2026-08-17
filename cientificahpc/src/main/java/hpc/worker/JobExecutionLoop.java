package hpc.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import hpc.cluster.NodeInfo;
import hpc.cluster.NodeRegistry;
import hpc.cluster.NodeType;
import hpc.home.HomeFetcher;
import hpc.home.HomePublisher;
import hpc.jobs.Job;
import hpc.jobs.JobQueue;
import hpc.runner.CompilationResult;
import hpc.runner.LanguageRunner;

/**
 * Background loop that actually runs jobs.
 *
 * RmiClusterServer only enqueues jobs and answers status/result queries —
 * it never blocks a remote call while a job compiles/runs. This class is
 * the piece that does the real work, in its own thread: it repeatedly
 * pulls the next QUEUED job from JobQueue, resolves its code/data
 * references via HomeFetcher, compiles and executes it via LanguageRunner
 * (CRunner, in practice), and reports the outcome back to JobQueue.
 *
 * This is also where the "no C-specific logic in the server" requirement
 * is satisfied: this class only talks to the LanguageRunner interface, so
 * swapping CRunner for another language implementation later would not
 * require touching this loop nor RmiClusterServer.
 */
public class JobExecutionLoop implements Runnable {

    private static final String SHARED_JOBS_ROOT = "/shared/jobs";
    private static final long POLL_INTERVAL_MS = 1000;
    // A job stuck longer than this is assumed to be hung because of an
    // unreachable node (mpirun itself has no built-in timeout), not because
    // the computation is legitimately taking that long — generous on purpose
    // for a course-project cluster, adjust if real workloads need more.
    private static final long EXECUTION_TIMEOUT_SECONDS = 300;
    private static final DateTimeFormatter RESULT_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private final JobQueue jobQueue;
    private final HomeFetcher homeFetcher;
    private final HomePublisher homePublisher;
    private final LanguageRunner languageRunner;
    private final NodeRegistry nodeRegistry;

    private volatile boolean running = true;

    public JobExecutionLoop(JobQueue jobQueue, HomeFetcher homeFetcher, HomePublisher homePublisher,
                             LanguageRunner languageRunner, NodeRegistry nodeRegistry) {
        this.jobQueue = jobQueue;
        this.homeFetcher = homeFetcher;
        this.homePublisher = homePublisher;
        this.languageRunner = languageRunner;
        this.nodeRegistry = nodeRegistry;
    }

    @Override
    public void run() {
        while (running) {
            Job job = jobQueue.pollNext();
            if (job == null) {
                sleep();
                continue;
            }
            runJob(job);
        }
    }

    /** Signals the loop to stop after its current iteration. */
    public void stop() {
        running = false;
    }

    private void runJob(Job job) {
        String workingDirectory = Paths.get(SHARED_JOBS_ROOT, job.getId()).toString();
        try {
            String sourceFilePath = homeFetcher.resolve(job.getCodeReference(), workingDirectory);
            String dataFilePath = homeFetcher.resolve(job.getDataReference(), workingDirectory);

            CompilationResult compilation = languageRunner.compile(sourceFilePath, workingDirectory);
            if (!compilation.isSuccess()) {
                jobQueue.markFailed(job.getId(), "Compilation error:\n" + compilation.getCompilerOutput());
                return;
            }

            List<String> nodes = nodeRegistry.listAvailableNodes(NodeType.FIXED).stream()
                    .map(NodeInfo::getHostname)
                    .collect(Collectors.toList());
            if (nodes.isEmpty()) {
                jobQueue.markFailed(job.getId(), "No FIXED nodes available to run the job.");
                return;
            }

            Process process = languageRunner.execute(
                    compilation.getBinaryPath(), List.of(dataFilePath), nodes, workingDirectory);

            // Drain stdout/stderr concurrently on a separate thread. If we waited
            // for output first (like compile() does) we would have no way to give
            // up early — readAllBytes() only returns once the process closes its
            // stream, i.e. once it has already finished.
            StringBuilder outputBuffer = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try {
                    outputBuffer.append(new String(process.getInputStream().readAllBytes()));
                } catch (IOException ignored) {
                    // Expected once the process is forcibly killed below; whatever
                    // was captured so far is still useful for diagnostics.
                }
            });
            outputReader.start();

            boolean finishedInTime = process.waitFor(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finishedInTime) {
                // Most likely cause: mpirun is stuck trying to reach a node that
                // is down/unreachable (SSH hanging, or MPI_Init waiting forever
                // for a rank that never checked in). Kill it so the queue does
                // not stay blocked forever — remember only one job runs at a time.
                process.destroyForcibly();
                outputReader.join(2000);
                jobQueue.markFailed(job.getId(),
                        "Job timed out after " + EXECUTION_TIMEOUT_SECONDS
                                + "s — likely an unreachable node. mpirun was force-stopped.\n"
                                + "Partial output:\n" + outputBuffer);
                return;
            }

            outputReader.join();
            String output = outputBuffer.toString();
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                publishResult(job, workingDirectory, output);
                jobQueue.markCompleted(job.getId(), output);
            } else {
                jobQueue.markFailed(job.getId(), "mpirun exited with code " + exitCode + ":\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobQueue.markFailed(job.getId(), "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Writes the job's output to a result file and publishes it back to
     * the Home, next to the original code/data (stage-out). This is a
     * best-effort side effect: if publishing fails, the job still counts
     * as COMPLETED, since the result remains available via getResult().
     */
    private void publishResult(Job job, String workingDirectory, String output) {
        try {
            // The filename carries both a timestamp (traceability: when this run
            // finished) and the job id (uniqueness: guaranteed not to collide even
            // if two runs finish within the same second). This way, running the
            // same code more than once never overwrites a previous result once it
            // lands in the Home — TestHomePublisher/RepositoryHomePublisher
            // preserve this filename when publishing.
            String timestamp = RESULT_TIMESTAMP_FORMAT.format(Instant.now());
            Path resultPath = Paths.get(workingDirectory, "result_" + timestamp + "_" + job.getId() + ".txt");
            Files.writeString(resultPath, output);
            homePublisher.publish(job.getCodeReference(), resultPath.toString());
        } catch (IOException | RuntimeException e) {
            System.err.println("Warning: failed to publish result for job " + job.getId() + ": " + e.getMessage());
        }
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
