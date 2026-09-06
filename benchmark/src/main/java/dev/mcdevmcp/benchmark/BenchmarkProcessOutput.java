package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * Bounded output captured from one child process.
 */
public record BenchmarkProcessOutput(int exitCode, String standardOutput, String standardError, boolean standardOutputOverflowed, boolean standardErrorOverflowed) {
    public BenchmarkProcessOutput {
        standardOutput = Objects.requireNonNull(standardOutput, "standardOutput");
        standardError = Objects.requireNonNull(standardError, "standardError");
    }
}
