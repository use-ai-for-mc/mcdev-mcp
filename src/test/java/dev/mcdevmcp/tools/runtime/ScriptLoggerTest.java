package dev.mcdevmcp.tools.runtime;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptLoggerTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void prunesRotatedFilesOlderThanRetentionAndKeepsRecentOnes(@TempDir Path temporary) throws Exception {
        long now = 2_000_000_000_000L;
        var diagnostics = new java.util.ArrayList<String>();
        var logger = new ScriptLogger(temporary, MAPPER, diagnostics::add, () -> false, InstantSource.fixed(Instant.ofEpochMilli(now)));

        Path logs = logger.logDirectory();
        Files.createDirectories(logs);

        // A rotated file far older than the 3-day retention window.
        Path ancient = logs.resolve("all.1000.jsonl");
        Files.writeString(ancient, "ancient");

        // A rotated file inside the retention window.
        Path recent = logs.resolve("all." + (now - 60_000L) + ".jsonl");
        Files.writeString(recent, "recent");

        // Make the live file exceed the rotation cap so that rotation triggers cleanup.
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();

        assertFalse(Files.exists(ancient), "3-day-old rotated file should be deleted");
        assertTrue(Files.exists(recent), "recent rotated file should be kept");
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void movesLiveFileToTimestampedRotationOnRotation(@TempDir Path temporary) throws Exception {
        long now = 2_000_000_000_000L;
        var logger = new ScriptLogger(temporary, MAPPER, new ArrayList<String>()::add, () -> true, InstantSource.fixed(Instant.ofEpochMilli(now)));

        Files.createDirectories(logger.logDirectory());
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();

        assertFalse(Files.exists(logger.allLogPath()), "oversized live file should be rotated away");
        assertTrue(Files.exists(logger.logDirectory().resolve("all." + now + ".jsonl")));
    }

    @Test
    void usesOneInjectedWallInstantPerRotationAndDoesNotLoseSameMillisecondFiles(@TempDir Path temporary) throws Exception {
        InstantSource wall = new ControlledInstantSource(Instant.ofEpochMilli(1_700_000_000_000L));
        var logger = new ScriptLogger(temporary, MAPPER, ignored -> {
        }, () -> true, wall);
        Files.createDirectories(logger.logDirectory());

        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();

        List<Path> rotations;
        try (var paths = Files.list(logger.logDirectory())) {
            rotations = paths.filter(path -> path.getFileName().toString().startsWith("all.")).sorted().toList();
        }
        assertEquals(2, rotations.size());
        assertTrue(rotations.stream().allMatch(path -> path.getFileName().toString().matches("all\\.-?\\d+\\.jsonl")));
    }

    @Test
    void retentionUsesExactCutoffFutureDatesAndBackwardWallMovement(@TempDir Path temporary) throws Exception {
        long nowMillis = 2_000_000_000_000L;
        var wall = new ControlledInstantSource(Instant.ofEpochMilli(nowMillis));
        var logger = new ScriptLogger(temporary, MAPPER, ignored -> {
        }, () -> false, wall);
        Files.createDirectories(logger.logDirectory());
        long retentionMillis = Duration.ofDays(3).toMillis();
        Path exact = logger.logDirectory().resolve("all." + (nowMillis - retentionMillis) + ".jsonl");
        Path old = logger.logDirectory().resolve("all." + (nowMillis - retentionMillis - 1) + ".jsonl");
        Path future = logger.logDirectory().resolve("all." + (nowMillis + 1) + ".jsonl");
        Files.writeString(exact, "exact");
        Files.writeString(old, "old");
        Files.writeString(future, "future");
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();
        assertTrue(Files.exists(exact));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(future));

        wall.set(Instant.ofEpochMilli(nowMillis - retentionMillis - 10_000));
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();
        assertTrue(Files.exists(exact), "backward wall movement must keep the prior rotation");
    }

    @Test
    void loggerFactoriesUseTheLoggerWallSource(@TempDir Path temporary) throws Exception {
        Instant timestamp = Instant.parse("2026-09-03T00:00:00Z");
        var logger = new ScriptLogger(temporary, MAPPER, ignored -> {
        }, () -> false, InstantSource.fixed(timestamp));
        logger.logCompleted(true, "return 1", true, 1, null, null, Duration.ofMillis(4));
        logger.logFailed("bad", "boom", Duration.ofMillis(5));
        List<String> lines = Files.readAllLines(logger.allLogPath());
        assertTrue(lines.stream().allMatch(line -> line.contains(timestamp.toString())));
    }

    @Test
    void retentionCutoffUnderflowSaturatesAtInstantMinimum(@TempDir Path temporary) throws Exception {
        var logger = new ScriptLogger(temporary, MAPPER, ignored -> {
        }, () -> false, InstantSource.fixed(Instant.MIN));
        Files.createDirectories(logger.logDirectory());
        Path oldest = logger.logDirectory().resolve("all." + Long.MIN_VALUE + ".jsonl");
        Files.writeString(oldest, "oldest");
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);

        logger.rotateIfNeeded();

        assertTrue(Files.exists(oldest));
    }

    @Test
    void upperRangeRotationsSurviveSameInstantCollisionAndNextCleanup(@TempDir Path temporary) throws Exception {
        InstantSource wall = InstantSource.fixed(Instant.MAX);
        var logger = new ScriptLogger(temporary, MAPPER, ignored -> {
        }, () -> false, wall);
        Files.createDirectories(logger.logDirectory());
        byte[] first = new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1];
        byte[] second = new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1];
        byte[] third = new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1];
        first[0] = 11;
        second[0] = 22;
        third[0] = 33;

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Files.write(logger.allLogPath(), first);
            logger.rotateIfNeeded();
            Files.write(logger.allLogPath(), second);
            logger.rotateIfNeeded();
            Files.write(logger.allLogPath(), third);
            logger.rotateIfNeeded();
        });

        Path firstRotation = logger.logDirectory().resolve("all." + Long.MAX_VALUE + ".jsonl");
        Path secondRotation = logger.logDirectory().resolve("all." + Long.MIN_VALUE + ".jsonl");
        Path thirdRotation = logger.logDirectory().resolve("all." + (Long.MIN_VALUE + 1) + ".jsonl");
        assertEquals(11, Files.readAllBytes(firstRotation)[0]);
        assertEquals(22, Files.readAllBytes(secondRotation)[0]);
        assertEquals(33, Files.readAllBytes(thirdRotation)[0]);
    }

    private static final class ControlledInstantSource implements InstantSource {
        private volatile Instant current;

        private ControlledInstantSource(Instant current) {
            this.current = current;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void set(Instant current) {
            this.current = current;
        }
    }
}
