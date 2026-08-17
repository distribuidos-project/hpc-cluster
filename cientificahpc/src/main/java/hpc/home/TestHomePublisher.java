package hpc.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Test implementation of HomePublisher: copies the result file into the
 * same local directory as the reference (simulating "next to the
 * original code/data in the Home"), without contacting any real Home
 * system. Mirrors TestHomeFetcher, but in the opposite direction.
 */
public class TestHomePublisher implements HomePublisher {

    @Override
    public void publish(String reference, String localResultPath) {
        Path referenceDirectory = Paths.get(reference).toAbsolutePath().getParent();
        Path resultSource = Paths.get(localResultPath);

        try {
            Files.createDirectories(referenceDirectory);
            Path destination = referenceDirectory.resolve(resultSource.getFileName().toString());
            Files.copy(resultSource, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to publish result: " + localResultPath, e);
        }
    }
}
