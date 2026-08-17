package hpc.jobs;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory FIFO implementation of JobQueue. Suitable for a single-server
 * deployment (no persistence across restarts), which matches the current
 * scope of the project.
 *
 * Enforces "only one job runs at a time": pollNext() returns null while a
 * job is already RUNNING, even if other jobs are QUEUED behind it.
 */
public class InMemoryJobQueue implements JobQueue {

    private final Queue<String> pending = new LinkedList<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private String runningJobId = null;

    @Override
    public synchronized String enqueue(String owner, String codeReference, String dataReference) {
        String jobId = UUID.randomUUID().toString();
        Job job = new Job(jobId, owner, codeReference, dataReference);
        jobs.put(jobId, job);
        pending.add(jobId);
        return jobId;
    }

    @Override
    public Job getJob(String jobId) {
        return jobs.get(jobId);
    }

    @Override
    public synchronized Job pollNext() {
        if (runningJobId != null) {
            return null; // a job is already running, wait until it finishes
        }
        String jobId = pending.poll();
        if (jobId == null) {
            return null; // nothing queued right now
        }
        Job job = jobs.get(jobId);
        job.setStatus(JobStatus.RUNNING);
        runningJobId = jobId;
        return job;
    }

    @Override
    public synchronized void markCompleted(String jobId, String result) {
        Job job = jobs.get(jobId);
        job.setStatus(JobStatus.COMPLETED);
        job.setResult(result);
        clearRunning(jobId);
    }

    @Override
    public synchronized void markFailed(String jobId, String errorMessage) {
        Job job = jobs.get(jobId);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        clearRunning(jobId);
    }

    @Override
    public synchronized void retry(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null || job.getStatus() != JobStatus.FAILED) {
            throw new IllegalStateException("Only a FAILED job can be retried: " + jobId);
        }
        job.setStatus(JobStatus.QUEUED);
        job.setErrorMessage(null);
        pending.add(jobId); // goes to the back of the queue, runs again from scratch
    }

    private void clearRunning(String jobId) {
        if (jobId.equals(runningJobId)) {
            runningJobId = null;
        }
    }
}
