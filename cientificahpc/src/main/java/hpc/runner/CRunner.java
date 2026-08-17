package hpc.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * LanguageRunner implementation for C programs parallelized with MPI
 * (OpenMPI) — the only language required by the project. Compiles with
 * mpicc and launches with mpirun, exactly like the manual tests already
 * validated on the VM cluster (hello.c, suma.c, pi.c).
 */
public class CRunner implements LanguageRunner {

    @Override
    public CompilationResult compile(String sourceFilePath, String workingDirectory) {
        try {
            Files.createDirectories(Paths.get(workingDirectory));
            String binaryPath = Paths.get(workingDirectory, "job.out").toString();

            ProcessBuilder builder = new ProcessBuilder("mpicc", sourceFilePath, "-o", binaryPath, "-lm");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = readOutput(process);
            int exitCode = process.waitFor();

            return new CompilationResult(exitCode == 0, exitCode == 0 ? binaryPath : null, output);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CompilationResult(false, null, "Compilation failed to start: " + e.getMessage());
        }
    }

    @Override
    public Process execute(String binaryPath, List<String> args, List<String> nodes, String workingDirectory) {
        try {
            String hostfilePath = writeHostfile(nodes, workingDirectory);

            List<String> command = new ArrayList<>();
            command.add("mpirun");
            command.add("--hostfile");
            command.add(hostfilePath);
            command.add("-np");
            command.add(String.valueOf(nodes.size()));
            command.add(binaryPath);
            command.addAll(args);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.directory(Paths.get(workingDirectory).toFile());
            return builder.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to launch MPI job: " + e.getMessage(), e);
        }
    }

    /** Writes an MPI hostfile ("hostname slots=1" per line) scoped to this job. */
    private String writeHostfile(List<String> nodes, String workingDirectory) throws IOException {
        Files.createDirectories(Paths.get(workingDirectory));
        Path hostfilePath = Paths.get(workingDirectory, "hostfile");
        StringBuilder content = new StringBuilder();
        for (String node : nodes) {
            content.append(node).append(" slots=1\n");
        }
        Files.writeString(hostfilePath, content.toString());
        return hostfilePath.toString();
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        return output.toString();
    }
}
