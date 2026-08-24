package hpc.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Real implementation of HomeFetcher: stage-in from the general
 * Repository's Home, mounted locally over NFS (see NfsHomeRoot and
 * stage_in_stage_out.md).
 *
 * "reference" is a path relative to the whole mounted Home tree, e.g.
 * "jperez/proyecto/suma.c" — the client already knows its own username
 * (it just authenticated as one) and its Home layout, the same way a
 * Shared File upload works. True access control on WHICH files a given
 * user's job may read is not re-implemented here: it falls out of the
 * same Unix-permissions-over-NFS mechanism the rest of the system
 * relies on (see cca-dir/INTEGRACION.md and internal/fs.Resolver in
 * cca-repo) — this class only guarantees a reference cannot escape the
 * mounted tree, not that it belongs to the caller.
 */
public class RepositoryHomeFetcher implements HomeFetcher {

    private final NfsHomeRoot nfsHome;

    public RepositoryHomeFetcher() {
        this(System.getProperty("hpc.home.nfsRoot", NfsHomeRoot.DEFAULT));
    }

    public RepositoryHomeFetcher(String nfsHomeRoot) {
        this.nfsHome = new NfsHomeRoot(nfsHomeRoot);
    }

    @Override
    public String resolve(String reference, String workingDirectory) {
        Path sourcePath = nfsHome.resolve(reference);
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Reference is not a regular file under the mounted Home: " + reference);
        }

        try {
            Files.createDirectories(Paths.get(workingDirectory));
            Path destination = Paths.get(workingDirectory, sourcePath.getFileName().toString());
            Files.copy(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage in reference from the Home: " + reference, e);
        }
    }
}
