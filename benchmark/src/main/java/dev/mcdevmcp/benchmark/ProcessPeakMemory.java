package dev.mcdevmcp.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Benchmark-only measurement; never substitutes heap usage for resident memory.
 */
final class ProcessPeakMemory {
    private static final int MAX_OUTPUT_BYTES = 128;
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private ProcessPeakMemory() {
    }

    static ProcessMemoryMetric metric() {
        return metric(System.getProperty("os.name"));
    }

    static ProcessMemoryMetric metric(String osName) {
        if (osName.equals("Linux")) return ProcessMemoryMetric.LINUX_VM_HWM;
        if (osName.startsWith("Windows")) return ProcessMemoryMetric.WINDOWS_PEAK_WORKING_SET;
        return ProcessMemoryMetric.UNAVAILABLE;
    }

    static long currentPeakBytes() throws IOException {
        return switch (metric()) {
            case LINUX_VM_HWM ->
                    linuxPeakBytes(Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.US_ASCII));
            case WINDOWS_PEAK_WORKING_SET -> {
                String root = System.getenv("SystemRoot");
                if (root == null || root.isBlank()) {
                    throw new IOException("SystemRoot is unavailable for Windows process-memory measurement");
                }
                Path executable = Path.of(root).resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
                if (!executable.isAbsolute() || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("System Windows PowerShell is unavailable");
                }
                yield positiveBytes(query(windowsCommand(executable, ProcessHandle.current().pid()), QUERY_TIMEOUT));
            }
            case UNAVAILABLE -> throw new IOException("Process peak resident memory is unavailable on this platform");
        };
    }

    static List<String> windowsCommand(Path executable, long pid) {
        if (pid <= 0) throw new IllegalArgumentException("Expected positive Java process ID");
        String script = "$ErrorActionPreference='Stop'; $p=[System.Diagnostics.Process]::GetProcessById(" + pid + "); try { $p.Refresh(); [Console]::Write($p.PeakWorkingSet64.ToString([System.Globalization.CultureInfo]::InvariantCulture)) } finally { $p.Dispose() }";
        return List.of(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
    }

    static long linuxPeakBytes(List<String> lines) throws IOException {
        Long result = null;
        for (String line : lines) {
            if (!line.startsWith("VmHWM:")) continue;
            String[] fields = line.trim().split("\\s+");
            if (result != null || fields.length != 3 || !fields[2].equals("kB")) {
                throw new IOException("Malformed or duplicate Linux VmHWM measurement");
            }
            try {
                result = Math.multiplyExact(positiveBytes(fields[1]), 1024L);
            } catch (ArithmeticException failure) {
                throw new IOException("Linux VmHWM measurement overflow", failure);
            }
        }
        if (result == null) throw new IOException("Linux process status did not provide VmHWM peak RSS");
        return result;
    }

    static long positiveBytes(String output) throws IOException {
        String value = output.strip();
        if (value.isEmpty() || value.chars().anyMatch(c -> c < '0' || c > '9')) {
            throw new IOException("Invalid process peak-memory measurement");
        }
        try {
            long bytes = Long.parseLong(value);
            if (bytes <= 0) throw new IOException("Process peak-memory measurement must be positive");
            return bytes;
        } catch (NumberFormatException failure) {
            throw new IOException("Process peak-memory measurement overflow", failure);
        }
    }

    static String query(List<String> command, Duration timeout) throws IOException {
        // Process.close waits without a deadline; cleanup below is bounded.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        FutureTask<byte[]> output = new FutureTask<>(() -> process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1));
        Thread reader = Thread.ofVirtual().name("process-memory-query-output").start(output);
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException failure = null;
        String result = null;
        try {
            byte[] bytes = output.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (bytes.length > MAX_OUTPUT_BYTES) {
                throw new IOException("Process-memory query exceeded its output limit");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw new IOException("Process-memory query timed out");
            }
            if (process.exitValue() != 0) throw new IOException("Process-memory query exited " + process.exitValue());
            result = new String(bytes, StandardCharsets.US_ASCII);
        } catch (IOException exception) {
            failure = exception;
        } catch (TimeoutException exception) {
            failure = new IOException("Process-memory query timed out", exception);
        } catch (ExecutionException exception) {
            failure = new IOException("Process-memory query output failed", exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = new IOException("Process-memory query interrupted", exception);
        } finally {
            try {
                cleanup(process, output, reader);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
        return result;
    }

    private static void cleanup(Process process, FutureTask<byte[]> output, Thread reader) throws IOException {
        boolean interrupted = Thread.interrupted();
        process.destroyForcibly();
        output.cancel(true);
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Process-memory query did not terminate");
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
            reader.join(Duration.ofSeconds(10));
            if (reader.isAlive()) throw new IOException("Process-memory query reader did not terminate");
        } catch (InterruptedException exception) {
            interrupted = true;
            throw new IOException("Process-memory query cleanup interrupted", exception);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}