package hpc.runner;

import java.io.Serializable;

/**
 * Result of a compilation attempt performed by a LanguageRunner.
 */
public class CompilationResult implements Serializable {

    private final boolean success;
    private final String binaryPath;
    private final String compilerOutput;

    public CompilationResult(boolean success, String binaryPath, String compilerOutput) {
        this.success = success;
        this.binaryPath = binaryPath;
        this.compilerOutput = compilerOutput;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public String getCompilerOutput() {
        return compilerOutput;
    }
}
