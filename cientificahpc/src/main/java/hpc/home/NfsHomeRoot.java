package hpc.home;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared logic between RepositoryHomeFetcher and RepositoryHomePublisher.
 *
 * cca-repo exports the general Repository's Home tree over NFS (port
 * 2049 — see stage_in_stage_out.md and the project notes on why cca-repo
 * is only ever reached this way, never through its HTTP API on 8443).
 * The cluster's master node mounts that export locally (e.g. "mount
 * cca-repo:/srv/home /srv/home"), so from this JVM's point of view a
 * user's file is just a regular local path: the kernel's VFS layer is
 * what actually reaches across the network underneath, transparently to
 * this code, exactly like the rest of the system's plain
 * new File(...)/Files.copy(...) calls.
 *
 * Both real Home implementations need to turn a client-supplied
 * reference (e.g. "jperez/proyecto/suma.c", relative to the whole
 * mounted tree, one path segment per username since this mount covers
 * every user's Home at once) into a real path guaranteed to live inside
 * that mount — and reject one that doesn't. That check is written once
 * here so it cannot drift between the two classes; it mirrors cca-repo's
 * own internal/fs.Resolver: collapse ".." against the root before the
 * filesystem is touched, then re-check with symlinks resolved, since a
 * symlink planted inside the tree could otherwise point outside of it
 * while still passing a plain string-prefix check.
 */
final class NfsHomeRoot {

    static final String DEFAULT = "/srv/home";

    private final Path root;

    NfsHomeRoot(String configuredRoot) {
        Path absolute = Paths.get(configuredRoot).toAbsolutePath().normalize();
        try {
            this.root = absolute.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "NFS Home mount not reachable at " + absolute
                            + " -- is cca-repo's export (port 2049) mounted there?", e);
        }
    }

    Path resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Reference must not be blank");
        }

        Path cleaned = Paths.get("/", reference).normalize();
        Path candidate = Paths.get(root.toString(), cleaned.toString());

        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            // May not exist yet (e.g. the destination of a stage-out that
            // hasn't been written before): resolve as far as the parent
            // and re-attach the file name, same fallback fs.Resolver uses.
            Path parent = candidate.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Reference not found under the mounted Home: " + reference);
            }
            try {
                real = parent.toRealPath().resolve(candidate.getFileName());
            } catch (IOException e2) {
                throw new IllegalArgumentException("Reference not found under the mounted Home: " + reference);
            }
        }

        if (!real.equals(root) && !real.startsWith(root)) {
            throw new IllegalArgumentException("Reference escapes the mounted Home: " + reference);
        }
        return real;
    }
}
