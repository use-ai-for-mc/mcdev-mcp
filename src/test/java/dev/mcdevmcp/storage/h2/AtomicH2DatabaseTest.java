package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SqlNoDataSourceInspection") // Each test creates an isolated database.
class AtomicH2DatabaseTest {
    @TempDir
    Path temporaryDirectory;

    private static void createDatabase(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            createMarker(connection, "old");
        }
    }

    private static void createMarker(Connection connection, String value) throws SQLException {
        SymbolSchema.create(connection, new MinecraftVersion("1.21.5"), Path.of("client"), "a".repeat(64), java.time.Instant.EPOCH);
        SymbolSchema.createIndexes(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (marker_value VARCHAR)");
            statement.executeUpdate("INSERT INTO marker(marker_value) VALUES ('" + value + "')");
        }
    }

    private static void createGenericMarker(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE generic_marker (marker_value VARCHAR)");
            statement.executeUpdate(genericMarkerInsertSql());
        }
    }

    private static void validateGenericMarker(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(genericMarkerSelectSql())) {
            if (!results.next()) {
                throw new SQLException("generic marker missing");
            }
        }
    }

    private static void validateMarker(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var results = statement.executeQuery(markerSelectSql())) {
            if (!results.next()) {
                throw new SQLException("marker missing");
            }
        }
    }

    private static void validateMarker(Connection connection, String expected) throws SQLException {
        try (var statement = connection.createStatement(); var results = statement.executeQuery(markerSelectSql())) {
            if (!results.next() || !expected.equals(results.getString(1))) {
                throw new SQLException("expected marker " + expected);
            }
        }
    }

    private static String marker(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(database));
             var statement = connection.createStatement(); var results = statement.executeQuery(markerSelectSql())) {
            assertTrue(results.next());
            return results.getString(1);
        }
    }

    @SuppressWarnings("SameReturnValue")
    private static String markerSelectSql() {
        return "SELECT marker_value FROM marker";
    }

    @SuppressWarnings("SameReturnValue")
    private static String genericMarkerInsertSql() {
        return "INSERT INTO generic_marker(marker_value) VALUES ('generic')";
    }

    @SuppressWarnings("SameReturnValue")
    private static String genericMarkerSelectSql() {
        return "SELECT marker_value FROM generic_marker";
    }

    @Test
    void buildsOneClosedMvStoreFileAndRejectsUnsafePaths() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");

        String result = new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return "built";
        }, AtomicH2DatabaseTest::validateMarker);

        assertEquals("built", result);
        assertEquals("new", marker(target));
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("symbols.lock.db")));
        assertFalse(Files.exists(temporaryDirectory.resolve("symbols.trace.db")));
        assertThrows(IllegalArgumentException.class, () -> new AtomicH2Database().rebuild(temporaryDirectory.resolve("bad;name.mv.db"), Duration.ofSeconds(1), _ -> null, _ -> {
        }));
    }

    @Test
    void acceptsH2PathsWithSpacesHashUnicodeAndWindowsSeparators() throws Exception {
        Path directory = temporaryDirectory.resolve("space # unicode-é");
        Path target = directory.resolve("symbols.mv.db");

        new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "special");
            return null;
        }, AtomicH2DatabaseTest::validateMarker);

        assertEquals("special", marker(target));
        assertTrue(H2DatabaseUrls.writer(target).contains(target.toAbsolutePath().getParent().toString()));
    }

    @Test
    void leavesOldDatabaseUnchangedWhenBuilderOrValidatorFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] oldBytes = Files.readAllBytes(target);

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new SQLException("builder failed");
        }, AtomicH2DatabaseTest::validateMarker));
        assertEquals("old", marker(target));
        assertEquals(java.util.Arrays.toString(oldBytes), java.util.Arrays.toString(Files.readAllBytes(target)));

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("validator failed");
        }));
        assertEquals("old", marker(target));
    }

    @Test
    void forcedBackupFallbackRestoresOldTargetWhenPostPromotionValidationFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);

        var moves = new ForcedFallbackMoveStrategy();
        var database = new AtomicH2Database(moves);
        assertThrows(SQLException.class, () -> database.rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("reject promoted database");
        }));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void forcedBackupFallbackPromotesAndDeletesTheBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);

        new AtomicH2Database(new ForcedFallbackMoveStrategy()).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker);

        assertEquals("new", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void forcedBackupFallbackRemovesInvalidTargetWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.mv.db");

        assertThrows(SQLException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy()).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("reject promoted database");
        }));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void commonInfrastructurePromotesANonSymbolH2Schema() throws Exception {
        Path target = temporaryDirectory.resolve("generic.mv.db");

        String result = new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createGenericMarker(connection);
            return "built";
        }, AtomicH2DatabaseTest::validateGenericMarker);

        assertEquals("built", result);
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(target));
             var statement = connection.createStatement();
             var results = statement.executeQuery(genericMarkerSelectSql())) {
            assertTrue(results.next());
            assertEquals("generic", results.getString(1));
        }
    }

    @Test
    void preservesOldTargetWhenTheFirstFallbackMoveFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void restoresOldTargetWhenTheFirstFallbackMoveFailsAfterMovingItToBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void preservesOldTargetAndPartialBackupWhenTheFirstFallbackMoveLeavesBoth() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_PARTIAL_COPY)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertTrue(Files.exists(backup));
        assertTrue(Files.size(backup) < original.length);
    }

    @Test
    void reportsWhenTheFirstFallbackMoveLeavesNeitherTargetNorBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SOURCE_REMOVAL)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(backup));
        assertTrue(java.util.Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed.getMessage().contains("Neither target nor backup remains after failed backup move")));
    }

    @Test
    void restoresOldTargetWhenTheSecondFallbackMoveFailsBeforeCreatingTheTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(2)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 2", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void restoresOldTargetWhenTheSecondFallbackMoveFailsAfterCreatingTheTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(2, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 2", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void removesAnUncertainTargetWhenTheFallbackMoveFailsAfterCreatingItWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.mv.db");

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void leavesNoTargetWhenTheFallbackMoveFailsBeforeCreatingItWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.mv.db");

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void preservesThePromotionFailureWhenBackupRestorationFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        var validations = new java.util.concurrent.atomic.AtomicInteger();

        SQLException failure = assertThrows(SQLException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(3)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            if (validations.incrementAndGet() == 3) {
                throw new SQLException("promotion validation failed");
            }
        }));

        assertEquals("promotion validation failed", failure.getMessage());
        assertTrue(java.util.Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed.getMessage().contains("Unable to restore backup")));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(backup));
        Path preserved = temporaryDirectory.resolve("preserved.mv.db");
        Files.copy(backup, preserved);
        assertEquals("old", marker(preserved));
    }

    @Test
    void keepsRestoredOldTargetAuthoritativeWhenBackupRestoreReportsFailureAfterMoving() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);
        var validations = new java.util.concurrent.atomic.AtomicInteger();

        SQLException failure = assertThrows(SQLException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(3, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            if (validations.incrementAndGet() == 3) {
                throw new SQLException("promotion validation failed");
            }
        }));

        assertEquals("promotion validation failed", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void preservesUncertainTargetAndBackupWhenTargetRemovalFailsBeforeRestore() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(2, ForcedFallbackMoveStrategy.FailureTiming.AFTER_SIDE_EFFECT, ForcedFallbackMoveStrategy.DeleteFailure.ANY)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("forced fallback move failure 2", failure.getMessage());
        assertTrue(java.util.Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed.getMessage().contains("Unable to remove uncertain promoted target")));
        assertEquals("new", marker(target));
        assertArrayEquals(original, Files.readAllBytes(backup));
    }

    @Test
    void neverOverwritesRestoredOldTargetAfterFormerPostRestoreCleanupFailure() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        Path rejected = target.resolveSibling("symbols.mv.db.failed-promotion");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);
        var validations = new java.util.concurrent.atomic.AtomicInteger();

        SQLException failure = assertThrows(SQLException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(0, ForcedFallbackMoveStrategy.FailureTiming.BEFORE_SIDE_EFFECT, ForcedFallbackMoveStrategy.DeleteFailure.FAILED_PROMOTION)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            if (validations.incrementAndGet() == 3) {
                throw new SQLException("promotion validation failed");
            }
        }));

        assertEquals("promotion validation failed", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(rejected));
    }

    @Test
    void preservesTemporaryDatabaseAndLockWhenTemporaryLockCompanionExists() {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path temporaryBase = temporaryDirectory.resolve("symbols." + ProcessHandle.current().pid() + ".tmp");
        Path temporary = temporaryBase.resolveSibling(temporaryBase.getFileName() + ".mv.db");
        Path lock = temporaryBase.resolveSibling(temporaryBase.getFileName() + ".lock.db");

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(lock)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertTrue(failure.getMessage().contains(lock.toString()));
        assertEquals(lock.toString(), failure.getSuppressed()[0].getMessage().replace("Refusing to rebuild while an H2 lock companion exists: ", ""));
        assertTrue(Files.exists(temporary));
        assertTrue(Files.exists(lock));
    }

    @Test
    void rejectsNumberedTemporaryCompanionWithoutTouchingUnrelatedSiblings() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        Path temporaryBase = temporaryDirectory.resolve("symbols." + ProcessHandle.current().pid() + ".tmp");
        Path numberedCompanion = temporaryBase.resolveSibling(temporaryBase.getFileName() + ".7.temp.db");
        Path unrelated = temporaryDirectory.resolve("unrelated.7.temp.db");
        Files.writeString(unrelated, "keep");

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(numberedCompanion)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));

        assertTrue(failure.getMessage().contains(numberedCompanion.toString()));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(numberedCompanion));
        assertEquals("keep", Files.readString(unrelated));
    }

    @Test
    void restoresBackupWhenStartupFindsNoTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        Files.move(target, backup);

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new SQLException("stop after recovery");
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void deletesStaleBackupWhenStartupFindsAValidTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        Files.copy(target, backup);

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new SQLException("stop after recovery");
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void routesExistingAndCandidateValidatorsSeparatelyDuringStaleBackupRecovery() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        Files.copy(target, backup);

        String result = new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return "built";
        }, connection -> validateMarker(connection, "old"), connection -> validateMarker(connection, "new"));

        assertEquals("built", result);
        assertEquals("new", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void preservesTargetAndBackupWhenStartupTargetIsInvalid() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        Files.copy(target, backup);
        Files.writeString(target, "invalid");

        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), _ -> null, AtomicH2DatabaseTest::validateMarker));

        assertTrue(failure.getMessage().contains(target.toString()));
        assertTrue(failure.getMessage().contains(backup.toString()));
        assertEquals("invalid", Files.readString(target));
        assertTrue(Files.exists(backup));
    }
}
