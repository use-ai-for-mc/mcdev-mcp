package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.PlatformPaths;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
class IndexCleanerTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");

    @TempDir
    Path temporaryDirectory;

    private static Process process(String mode, Path database) throws Exception {
        String java = System.getProperty("mcdevMcpJava");
        String classpath = System.getProperty("java.class.path");
        return new ProcessBuilder(java, "-cp", classpath, DatabaseLockProcessMain.class.getName(), mode, database.toString()).start();
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void createDatabase(Path database) throws Exception {
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection(H2DatabaseUrls.writer(database));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (marker_value VARCHAR)");
            //noinspection SqlNoDataSourceInspection,SqlResolve
            statement.execute("INSERT INTO marker(marker_value) VALUES ('old')");
        }
    }

    private static String marker(Path database) throws Exception {
        //noinspection SqlNoDataSourceInspection,SqlResolve
        try (var connection = DriverManager.getConnection(H2DatabaseUrls.reader(database));
             var statement = connection.createStatement();
             var results = statement.executeQuery("SELECT marker_value FROM marker")) {
            assertTrue(results.next());
            return results.getString(1);
        }
    }

    @Test
    void externalH2ReaderAppearingBeforeDatabaseGuardPreventsAnyDeletion() throws Exception {
        assertExternalH2UserAppearingBeforeDatabaseGuardPreventsAnyDeletion("hold-h2-read");
    }

    @Test
    void externalH2WriterAppearingBeforeDatabaseGuardPreventsAnyDeletion() throws Exception {
        assertExternalH2UserAppearingBeforeDatabaseGuardPreventsAnyDeletion("hold-h2-write");
    }

    private void assertExternalH2UserAppearingBeforeDatabaseGuardPreventsAnyDeletion(String mode) throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolDatabase(VERSION);
        Path legacy = paths.indexRoot(VERSION).resolve("legacy.json");
        createDatabase(database);
        Files.writeString(legacy, "keep");
        Process externalH2 = null;
        var cleanerStarted = new CountDownLatch(1);
        var appReader = DatabaseLock.read(database, Duration.ofSeconds(1));
        boolean appReaderClosed = false;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var clean = executor.submit(() -> {
                cleanerStarted.countDown();
                new IndexCleaner(paths).cleanIndex(VERSION);
                return null;
            });
            assertTrue(cleanerStarted.await(2, TimeUnit.SECONDS));
            externalH2 = process(mode, database);
            try (var output = new BufferedReader(new InputStreamReader(externalH2.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("h2-open", output.readLine());
                appReader.close();
                appReaderClosed = true;
                ExecutionException failure = assertThrows(ExecutionException.class, () -> clean.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("exclusive H2 database file lock"));
                assertTrue(Files.exists(database));
                assertEquals("keep", Files.readString(legacy));
                externalH2.getOutputStream().close();
                assertTrue(externalH2.waitFor(5, TimeUnit.SECONDS));
            }
            assertEquals("old", marker(database));
        } finally {
            if (!appReaderClosed) {
                appReader.close();
            }
            if (externalH2 != null) {
                stop(externalH2);
            }
        }
    }

    @Test
    void cleanerDatabaseGuardPreventsARealH2ProcessOpeningDuringDeletion() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolDatabase(VERSION);
        Path legacy = paths.indexRoot(VERSION).resolve("legacy.json");
        createDatabase(database);
        Files.writeString(legacy, "delete");
        var files = new PausingDatabaseFileOperations();
        Process externalH2 = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var clean = executor.submit(() -> {
                new IndexCleaner(paths, files).cleanIndex(VERSION);
                return null;
            });
            assertTrue(files.awaitDeletion());
            externalH2 = process("try-h2-write", database);
            try (var output = new BufferedReader(new InputStreamReader(externalH2.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("blocked", output.readLine());
                assertTrue(externalH2.waitFor(5, TimeUnit.SECONDS));
            } finally {
                files.continueDeletion();
            }
            clean.get(5, TimeUnit.SECONDS);
        } finally {
            files.continueDeletion();
            if (externalH2 != null) {
                stop(externalH2);
            }
        }
        assertFalse(Files.exists(database));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void refusesAndPreservesAnH2LockCompanionEncounteredDuringTraversal() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolDatabase(VERSION);
        Path legacy = paths.indexRoot(VERSION).resolve("legacy.json");
        Path h2Lock = database.resolveSibling("symbols.lock.db");
        createDatabase(database);
        Files.writeString(legacy, "delete or preserve");
        var files = new PausingDatabaseFileOperations();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var clean = executor.submit(() -> {
                new IndexCleaner(paths, files).cleanIndex(VERSION);
                return null;
            });
            assertTrue(files.awaitDeletion());
            Files.writeString(h2Lock, "appeared during traversal");
            files.continueDeletion();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> clean.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains(h2Lock.toString()));
        } finally {
            files.continueDeletion();
        }
        assertTrue(Files.exists(h2Lock));
        assertTrue(Files.exists(database));
    }

    @Test
    void removesAnAbsentDatabaseReservationAfterCleaning() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolDatabase(VERSION);
        Files.createDirectories(database.getParent());
        Files.writeString(database.resolveSibling("legacy.json"), "delete");

        new IndexCleaner(paths).cleanIndex(VERSION);

        assertFalse(Files.exists(database));
        assertFalse(Files.exists(database.resolveSibling("legacy.json")));
        assertTrue(Files.exists(database.resolveSibling("symbols.mv.db.lock")));
    }

    @Test
    void rejectsSymlinkedVersionIndexRootBeforeCreatingTheApplicationLock() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory.resolve("cache-root"));
        Path root = paths.indexRoot(VERSION);
        Path outside = temporaryDirectory.resolve("outside-index");
        Files.createDirectories(root.getParent());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.json"), "keep");
        Files.createSymbolicLink(root, outside);

        IOException failure = assertThrows(IOException.class, () -> new IndexCleaner(paths).cleanIndex(VERSION));

        assertTrue(failure.getMessage().contains("symbolic link"));
        assertEquals("keep", Files.readString(outside.resolve("keep.json")));
        assertFalse(Files.exists(outside.resolve("symbols.mv.db.lock")));
    }

    @Test
    void rejectsSymlinkedSymbolDatabaseBeforeCreatingTheApplicationLock() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory.resolve("cache-root"));
        Path database = paths.symbolDatabase(VERSION);
        Path outside = temporaryDirectory.resolve("outside.mv.db");
        Files.createDirectories(database.getParent());
        Files.writeString(outside, "keep");
        Files.writeString(database.resolveSibling("legacy.json"), "keep");
        Files.createSymbolicLink(database, outside);

        IOException failure = assertThrows(IOException.class, () -> new IndexCleaner(paths).cleanIndex(VERSION));

        assertTrue(failure.getMessage().contains("symbolic link"));
        assertEquals("keep", Files.readString(outside));
        assertEquals("keep", Files.readString(database.resolveSibling("legacy.json")));
        assertFalse(Files.exists(database.resolveSibling("symbols.mv.db.lock")));
    }
}