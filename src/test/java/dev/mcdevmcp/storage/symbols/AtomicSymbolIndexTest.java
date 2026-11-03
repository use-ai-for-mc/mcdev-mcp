package dev.mcdevmcp.storage.symbols;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AtomicSymbolIndexTest {
    private static final String MARKER = "marker:";
    @TempDir
    Path temporaryDirectory;

    private static void createDatabase(Path database) throws IOException {
        createMarker(database, "old");
    }

    private static void createMarker(Path file, String value) throws IOException {
        Files.writeString(file, MARKER + value);
    }

    private static void validateMarker(Path file) throws IOException {
        if (!Files.readString(file).startsWith(MARKER)) {
            throw new IOException("marker missing");
        }
    }

    private static void validateMarker(Path file, String expected) throws IOException {
        if (!marker(file).equals(expected)) {
            throw new IOException("expected marker " + expected);
        }
    }

    private static String marker(Path database) throws IOException {
        String content = Files.readString(database);
        assertTrue(content.startsWith(MARKER));
        return content.substring(MARKER.length());
    }

    @Test
    void buildsOneFileAndLeavesNoTemporaryBehind() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        String result = new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return "built";
        }, AtomicSymbolIndexTest::validateMarker);
        assertEquals("built", result);
        assertEquals("new", marker(target));
        try (var siblings = Files.list(temporaryDirectory)) {
            assertEquals(List.of("symbols.jsonl", "symbols.jsonl.lock"), siblings.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void acceptsPathsWithSpacesHashAndUnicode() throws Exception {
        Path directory = temporaryDirectory.resolve("space # unicode-é");
        Path target = directory.resolve("symbols.jsonl");

        new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "special");
            return null;
        }, AtomicSymbolIndexTest::validateMarker);

        assertEquals("special", marker(target));
    }

    @Test
    void leavesOldDatabaseUnchangedWhenBuilderOrValidatorFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);
        byte[] oldBytes = Files.readAllBytes(target);

        assertThrows(IOException.class, () -> new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new IOException("builder failed");
        }, AtomicSymbolIndexTest::validateMarker));
        assertEquals("old", marker(target));
        assertEquals(Arrays.toString(oldBytes), Arrays.toString(Files.readAllBytes(target)));

        assertThrows(IOException.class, () -> new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, _ -> {
            throw new IOException("validator failed");
        }));
        assertEquals("old", marker(target));
    }

    @Test
    void forcedBackupFallbackRestoresOldTargetWhenPostPromotionValidationFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);

        var moves = new ForcedFallbackMoveStrategy();
        var database = new AtomicSymbolIndex(moves);
        assertThrows(IOException.class, () -> database.rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, _ -> {
            throw new IOException("reject promoted database");
        }));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void forcedBackupFallbackPromotesAndDeletesTheBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);

        new AtomicSymbolIndex(new ForcedFallbackMoveStrategy()).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker);

        assertEquals("new", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void forcedBackupFallbackRemovesInvalidTargetWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.jsonl");

        assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy()).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, _ -> {
            throw new IOException("reject promoted database");
        }));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void preservesOldTargetWhenTheFirstFallbackMoveFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(1)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void restoresOldTargetWhenTheFirstFallbackMoveFailsAfterMovingItToBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void preservesOldTargetAndPartialBackupWhenTheFirstFallbackMoveLeavesBoth() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_PARTIAL_COPY)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertTrue(Files.exists(backup));
        assertTrue(Files.size(backup) < original.length);
    }

    @Test
    void reportsWhenTheFirstFallbackMoveLeavesNeitherTargetNorBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SOURCE_REMOVAL)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(backup));
        assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed.getMessage().contains("Neither target nor backup remains after failed backup move")));
    }

    @Test
    void restoresOldTargetWhenTheSecondFallbackMoveFailsBeforeCreatingTheTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(2)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 2", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void restoresOldTargetWhenTheSecondFallbackMoveFailsAfterCreatingTheTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(2, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 2", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.jsonl.bak")));
    }

    @Test
    void removesAnUncertainTargetWhenTheFallbackMoveFailsAfterCreatingItWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.jsonl");

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void leavesNoTargetWhenTheFallbackMoveFailsBeforeCreatingItWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.jsonl");

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(1)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void preservesThePromotionFailureWhenBackupRestorationFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        var validations = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(3)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, _ -> {
            if (validations.incrementAndGet() == 2) {
                throw new IOException("promotion validation failed");
            }
        }));

        assertEquals("promotion validation failed", failure.getMessage());
        assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed.getMessage().contains("Unable to restore backup")));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(backup));
        Path preserved = temporaryDirectory.resolve("preserved.jsonl");
        Files.copy(backup, preserved);
        assertEquals("old", marker(preserved));
    }

    @Test
    void keepsRestoredOldTargetAuthoritativeWhenBackupRestoreReportsFailureAfterMoving() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);
        var validations = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(3, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, _ -> {
            if (validations.incrementAndGet() == 2) {
                throw new IOException("promotion validation failed");
            }
        }));

        assertEquals("promotion validation failed", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void preservesUncertainTargetAndBackupWhenTargetRemovalFailsBeforeRestore() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(2, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT, ForcedFallbackMoveStrategy.DeleteFailure.ANY)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("forced fallback move failure 2", failure.getMessage());
        assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed.getMessage().contains("Unable to remove uncertain promoted target")));
        assertEquals("new", marker(target));
        assertArrayEquals(original, Files.readAllBytes(backup));
    }

    @Test
    void neverOverwritesRestoredOldTargetAfterFormerPostRestoreCleanupFailure() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        Path rejected = target.resolveSibling("symbols.jsonl.failed-promotion");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);
        var validations = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex(new ForcedFallbackMoveStrategy(0, ForcedFallbackMoveStrategy.FailureTiming.BEFORE_SIDE_EFFECT, ForcedFallbackMoveStrategy.DeleteFailure.FAILED_PROMOTION)).rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return null;
        }, _ -> {
            if (validations.incrementAndGet() == 2) {
                throw new IOException("promotion validation failed");
            }
        }));

        assertEquals("promotion validation failed", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(rejected));
    }

    @Test
    void restoresBackupWhenStartupFindsNoTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        Files.move(target, backup);

        assertThrows(IOException.class, () -> new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new IOException("stop after recovery");
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void deletesStaleBackupWhenStartupFindsAValidTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        Files.copy(target, backup);

        assertThrows(IOException.class, () -> new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new IOException("stop after recovery");
        }, AtomicSymbolIndexTest::validateMarker));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void routesExistingAndCandidateValidatorsSeparatelyDuringStaleBackupRecovery() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        Files.copy(target, backup);

        String result = new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), temporary -> {
            createMarker(temporary, "new");
            return "built";
        }, file -> validateMarker(file, "old"), file -> validateMarker(file, "new"));

        assertEquals("built", result);
        assertEquals("new", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void preservesTargetAndBackupWhenStartupTargetIsInvalid() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = target.resolveSibling("symbols.jsonl.bak");
        createDatabase(target);
        Files.copy(target, backup);
        Files.writeString(target, "invalid");

        IOException failure = assertThrows(IOException.class, () -> new AtomicSymbolIndex().rebuild(target, Duration.ofSeconds(1), _ -> null, AtomicSymbolIndexTest::validateMarker));

        assertTrue(failure.getMessage().contains(target.toString()));
        assertTrue(failure.getMessage().contains(backup.toString()));
        assertEquals("invalid", Files.readString(target));
        assertTrue(Files.exists(backup));
    }
}
