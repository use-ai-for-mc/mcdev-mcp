package dev.mcdevmcp.app;

import dev.mcdevmcp.support.ProgressSink;

import java.io.PrintWriter;
import java.util.Objects;

public final class CliProgressSink {
    private CliProgressSink() {
    }

    public static ProgressSink forWriter(PrintWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return (stage, percent, message) -> {
            writer.printf("[%s] %d%% - %s%n", stage, Math.clamp(percent, 0, 100), message);
            writer.flush();
        };
    }
}