package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ScriptLogger {
    static final long MAX_LOG_BYTES = 10L * 1024 * 1024;

    // Keep rotated session-log files for at most 3 days so explicit session logging
    // cannot grow the data directory without bound on a long-lived server.
    private static final Duration ROTATION_RETENTION = Duration.ofDays(3);

    private static final Pattern LINE_NUMBER = Pattern.compile("line (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON_NUMBER = Pattern.compile(":\\d+:");
    private static final Pattern QUOTED_VALUE = Pattern.compile("'[^']+'");

    private final Path logDirectory;
    private final Path allLog;
    private final Path errorsLog;
    private final McpJsonMapper mapper;
    private final Consumer<String> diagnostics;
    private final BooleanSupplier rotationSample;
    private final InstantSource wallTime;
    private boolean rotating;

    ScriptLogger(Path dataDirectory, McpJsonMapper mapper, Consumer<String> diagnostics) {
        this(dataDirectory, mapper, diagnostics, () -> ThreadLocalRandom.current().nextDouble() < 0.01, InstantSource.system());
    }

    ScriptLogger(Path dataDirectory, McpJsonMapper mapper, Consumer<String> diagnostics, BooleanSupplier rotationSample, InstantSource wallTime) {
        logDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").resolve("script-logs").normalize();
        allLog = logDirectory.resolve("all.jsonl");
        errorsLog = logDirectory.resolve("errors.jsonl");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.rotationSample = Objects.requireNonNull(rotationSample, "rotationSample");
        this.wallTime = Objects.requireNonNull(wallTime, "wallTime");
    }

    static Path dataDirectory(String osName, AppEnvironment environment, Path home) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(home, "home");
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Path local = environment.value("LOCALAPPDATA").filter(value -> !value.isBlank()).map(Path::of).orElseGet(() -> home.resolve("AppData").resolve("Local"));
            return local.resolve("mcdev-mcp").resolve("Data");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return home.resolve("Library").resolve("Application Support").resolve("mcdev-mcp");
        }
        Path data = environment.value("XDG_DATA_HOME").filter(value -> !value.isBlank()).map(Path::of).orElseGet(() -> home.resolve(".local").resolve("share"));
        return data.resolve("mcdev-mcp");
    }

    private static String normalizeError(String error) {
        String normalized = LINE_NUMBER.matcher(error).replaceAll("line N");
        normalized = COLON_NUMBER.matcher(normalized).replaceAll(":N:");
        normalized = QUOTED_VALUE.matcher(normalized).replaceAll("'...'");
        return normalized.substring(0, Math.min(200, normalized.length()));
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }

    synchronized void log(ScriptLogEntry entry, boolean allowRotation) {
        append(allLog, entry);
        if (!entry.success()) {
            append(errorsLog, entry);
        }
        if (allowRotation && rotationSample.getAsBoolean()) {
            rotateIfNeeded();
        }
    }

    synchronized void rotateIfNeeded() {
        if (rotating) {
            return;
        }
        rotating = true;
        try {
            Instant now = wallTime.instant();
            rotate(allLog, now);
            rotate(errorsLog, now);
        } finally {
            rotating = false;
        }
    }

    synchronized List<ScriptLogEntry> recentErrors(int limit) {
        if (limit <= 0 || !Files.exists(errorsLog)) {
            return List.of();
        }
        try {
            List<ScriptLogEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(errorsLog, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Object decoded = mapper.readValue(line.getBytes(StandardCharsets.UTF_8), Object.class);
                    if (decoded instanceof Map<?, ?> values) {
                        entries.add(ScriptLogWireEntry.fromJson(values).toDomain());
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
            return List.copyOf(entries.subList(Math.max(0, entries.size() - limit), entries.size()));
        } catch (IOException exception) {
            return List.of();
        }
    }

    void logCompleted(boolean success, String code, boolean resultPresent, Object result, String output, String error, Duration duration) {
        log(ScriptLogEntry.completed(wallTime.instant(), success, code, resultPresent, result, output, error, duration), true);
    }

    void logFailed(String code, String error, Duration duration) {
        log(ScriptLogEntry.failed(wallTime.instant(), code, error, duration), false);
    }

    synchronized List<ScriptErrorStat> errorStats() {
        Map<String, MutableErrorStat> grouped = new LinkedHashMap<>();
        for (ScriptLogEntry entry : recentErrors(500)) {
            if (entry.error() == null) {
                continue;
            }
            String normalized = normalizeError(entry.error());
            MutableErrorStat stat = grouped.computeIfAbsent(normalized, _ -> new MutableErrorStat());
            stat.count++;
            stat.lastSeen = entry.timestamp();
            if (stat.examples.size() < 3 && !stat.examples.contains(entry.code())) {
                stat.examples.add(entry.code());
            }
        }
        return grouped.entrySet().stream().map(entry -> new ScriptErrorStat(entry.getKey(), entry.getValue().count, entry.getValue().lastSeen, List.copyOf(entry.getValue().examples))).sorted(Comparator.comparingInt(ScriptErrorStat::count).reversed()).toList();
    }

    Path allLogPath() {
        return allLog;
    }

    Path errorsLogPath() {
        return errorsLog;
    }

    Path logDirectory() {
        return logDirectory;
    }

    private void append(Path path, ScriptLogEntry entry) {
        try {
            Files.createDirectories(logDirectory);
            Files.write(path, jsonLine(entry), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            diagnostics.accept("[ScriptLogger] Failed to write log: " + exception);
        }
    }

    private byte[] jsonLine(ScriptLogEntry entry) throws IOException {
        ScriptLogWireEntry wire = ScriptLogWireEntry.fromDomain(entry);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("timestamp", wire.timestamp());
        values.put("success", wire.success());
        values.put("code", wire.code());
        if (wire.resultPresent()) {
            values.put("result", wire.result());
        }
        if (wire.output() != null) {
            values.put("output", wire.output());
        }
        if (wire.error() != null) {
            values.put("error", wire.error());
        }
        values.put("duration_ms", wire.duration_ms());
        byte[] json = mapper.writeValueAsBytes(values);
        byte[] line = java.util.Arrays.copyOf(json, json.length + 1);
        line[json.length] = '\n';
        return line;
    }

    private void rotate(Path live, Instant now) {
        try {
            if (!Files.exists(live) || Files.size(live) <= MAX_LOG_BYTES) {
                return;
            }
            Path rotated = uniqueRotationPath(live, now);
            Files.move(live, rotated);
            cleanOldRotations(live, now);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private Path uniqueRotationPath(Path live, Instant now) {
        String baseName = baseName(live);
        long timestamp = epochMillis(now);
        Path rotated = live.resolveSibling(baseName + "." + timestamp + ".jsonl");
        while (Files.exists(rotated)) {
            if (timestamp == Long.MAX_VALUE) {
                timestamp = Long.MIN_VALUE;
            }
            else {
                timestamp++;
            }
            rotated = live.resolveSibling(baseName + "." + timestamp + ".jsonl");
        }
        return rotated;
    }

    private static long epochMillis(Instant instant) {
        try {
            return instant.toEpochMilli();
        } catch (ArithmeticException exception) {
            return instant.isBefore(Instant.EPOCH) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private void cleanOldRotations(Path live, Instant now) throws IOException {
        if (!hasEpochMilliRepresentation(now)) {
            return;
        }
        String baseName = baseName(live);
        Instant cutoff = retentionCutoff(now);
        try (Stream<Path> paths = Files.list(logDirectory)) {
            List<Path> rotations = paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(baseName + ".") && name.endsWith(".jsonl") && !path.equals(live);
            }).toList();
            for (Path old : rotations) {
                Instant timestamp = rotationTimestamp(old.getFileName().toString(), baseName.length());
                if (timestamp != null && timestamp.isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(old);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static boolean hasEpochMilliRepresentation(Instant instant) {
        try {
            instant.toEpochMilli();
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static Instant retentionCutoff(Instant now) {
        try {
            return now.minus(ROTATION_RETENTION);
        } catch (DateTimeException | ArithmeticException exception) {
            return Instant.MIN;
        }
    }

    // Parses the epoch-millis suffix from a rotation file name such as "all.1700000000000.jsonl".
    // The millis start immediately after the "<base>." prefix.
    private static Instant rotationTimestamp(String name, int baseNameLength) {
        int start = baseNameLength + 1;
        int end = name.length() - ".jsonl".length();
        if (end <= start) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(name.substring(start, end)));
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    record ScriptLogEntry(Instant timestamp, boolean success, String code, boolean resultPresent, Object result, String output, String error, Duration duration) {
        ScriptLogEntry {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(duration, "duration");
        }

        static ScriptLogEntry completed(Instant timestamp, boolean success, String code, boolean resultPresent, Object result, String output, String error, Duration duration) {
            return new ScriptLogEntry(timestamp, success, code, resultPresent, result, output, error, duration);
        }

        static ScriptLogEntry failed(Instant timestamp, String code, String error, Duration duration) {
            return new ScriptLogEntry(timestamp, false, code, false, null, null, error, duration);
        }
    }

    record ScriptErrorStat(String error, int count, Instant lastSeen, List<String> examples) {
    }

    private static final class MutableErrorStat {
        private final List<String> examples = new ArrayList<>();
        private int count;
        private Instant lastSeen;
    }
}
