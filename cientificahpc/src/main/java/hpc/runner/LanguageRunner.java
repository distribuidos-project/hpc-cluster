package hpc.runner;

import java.util.List;

/**
 * Contract for compiling and executing a submitted job.
 *
 * Only one implementation exists for now (CRunner, for C + MPI), but the
 * RMI orchestrator always talks to this interface, never to a specific
 * language directly. Adding support for another language later just means
 * writing a new class that implements this interface, without touching
 * the rest of the system.
 */
public interface LanguageRunner {

    /**
     * Compiles an already-materialized source file into an executable binary.
     *
     * The source file is expected to already exist on disk (written there by
     * HomeFetcher when it resolved the job's codeReference) — this method
     * never receives source code as an in-memory String, to keep the whole
     * pipeline consistent about moving files by path/reference instead of
     * by content.
     *
     * @param sourceFilePath   path to the source file to compile (inside the
     *                         shared repository, reachable from every node)
     * @param workingDirectory directory where the resulting binary is written
     * @return the result of the compilation (success flag, binary path, compiler output)
     */
    CompilationResult compile(String sourceFilePath, String workingDirectory);

    /**
     * Launches the compiled binary in parallel across the given nodes.
     *
     * @param binaryPath       path to the compiled binary (must be reachable
     *                         from every node, e.g. via the shared repository)
     * @param args             arguments passed to the binary (e.g. the path to
     *                         the input data file, written beforehand into
     *                         workingDirectory)
     * @param nodes            hostnames/IPs of the nodes where the job should run
     * @param workingDirectory directory where output/result files should be written
     * @return a handle to the running process, used to track its status and exit code later
     */
    Process execute(String binaryPath, List<String> args, List<String> nodes, String workingDirectory);
}
