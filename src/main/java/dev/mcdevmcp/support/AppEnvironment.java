package dev.mcdevmcp.support;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public record AppEnvironment(Map<String, String> values) {
    private static final Pattern DEBUGBRIDGE_PORT_DECIMAL = Pattern.compile("\\A[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?\\z");

    public AppEnvironment {
        values = Map.copyOf(values);
    }

    public static AppEnvironment system() {
        return new AppEnvironment(System.getenv());
    }

    public Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public boolean isTruthy(String name) {
        return value(name).map(value -> value.toLowerCase(Locale.ROOT)).map(value -> value.equals("1") || value.equals("true")).orElse(false);
    }

    public Optional<Path> debugLogPath() {
        return value("MCDEV_MCP_DEBUG_LOG").filter(value -> !value.isEmpty()).filter(value -> !value.equals("off")).map(value -> value.equals("on") ? Path.of("/tmp/mcdev-debug.log") : Path.of(value));
    }

    public OptionalInt debugBridgePort() {
        String configured = values.get("DEBUGBRIDGE_PORT");
        if (configured == null) {
            return OptionalInt.empty();
        }
        String text = configured.strip();
        if (!DEBUGBRIDGE_PORT_DECIMAL.matcher(text).matches()) {
            return OptionalInt.empty();
        }
        try {
            int port = new BigDecimal(text).intValueExact();
            return port >= 1 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (ArithmeticException | NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    public int indexThreads(int availableProcessors) {
        int maximum = Math.max(1, availableProcessors);
        return value("MCDEV_INDEX_THREADS").flatMap(this::positiveInteger).map(value -> Math.min(value, maximum)).orElse(maximum);
    }

    private Optional<Integer> positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
