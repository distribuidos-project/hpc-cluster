package hpc.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Real implementation of HomePublisher: stage-out to the general
 * Repository's Home, mounted locally over NFS (see NfsHomeRoot and
 * stage_in_stage_out.md) — the mirror operation of
 * RepositoryHomeFetcher's stage-in.
 *
 * After the result lands back in the Home, the local working directory
 * (the staged-in copies of the code/data plus the result file) is
 * deleted: the nodes only ever needed those copies locally for this one
 * run (low-latency access during compile/execute), and once the Home
 * has its own copy of the result, that scratch space serves no further
 * purpose. This is best-effort and deliberately does not affect the
 * job's outcome: HomePublisher is documented as fire-and-forget, and
 * JobExecutionLoop already treats any RuntimeException from publish() as
 * a warning, not a failure — getResult() over RMI remains the
 * authoritative result regardless of what happens here.
 */
public class RepositoryHomePublisher implements HomePublisher {

    private final NfsHomeRoot nfsHome;

    public RepositoryHomePublisher() {
        this(System.getProperty("hpc.home.nfsRoot", NfsHomeRoot.DEFAULT));
    }

    public RepositoryHomePublisher(String nfsHomeRoot) {
        this.nfsHome = new NfsHomeRoot(nfsHomeRoot);
    }

    @Override
    public void publish(String reference, String localResultPath) {
        Path originalFile = nfsHome.resolve(reference);
        Path resultSource = Paths.get(localResultPath);

        try {
            Path destination = originalFile.getParent().resolve(resultSource.getFileName());
            Files.copy(resultSource, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to publish result: " + localResultPath, e);
        }

        cleanUpWorkingDirectory(resultSource.getParent());
    }

    private static void cleanUpWorkingDirectory(Path workingDirectory) {
        if (workingDirectory == null || !Files.exists(workingDirectory)) {
            return;
        }
        try {
            Files.walkFileTree(workingDirectory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println(
                    "Warning: failed to clean up working directory " + workingDirectory + ": " + e.getMessage());
        }
    }
}
