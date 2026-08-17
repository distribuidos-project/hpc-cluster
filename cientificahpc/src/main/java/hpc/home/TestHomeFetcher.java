package hpc.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Test implementation of HomeFetcher: assumes the reference is already a
 * valid local path on the cluster's shared storage (e.g. the client wrote
 * the file there directly, since for local testing the client can reach
 * the same NFS share), and just copies it into the job's working
 * directory. Does not contact any real Home/Repository system.
 *
 * This lets the whole orchestrator pipeline be developed and tested end
 * to end without depending on the teammate's Home integration being ready.
 */
public class TestHomeFetcher implements HomeFetcher {

    @Override
    public String resolve(String reference, String workingDirectory) {
        Path sourcePath = Paths.get(reference);
        if (!Files.exists(sourcePath)) {
            throw new IllegalArgumentException("Reference not found: " + reference);
        }

        try {
            Files.createDirectories(Paths.get(workingDirectory));
            Path destination = Paths.get(workingDirectory, sourcePath.getFileName().toString());
            Files.copy(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve reference: " + reference, e);
        }
    }
}
