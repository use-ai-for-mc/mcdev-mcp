package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IndexLockProcessTest {
    @TempDir
    Path temporaryDirectory;

    private static Process process(String mode, Path database) throws Exception {
        String java = System.getProperty("mcdevMcpJava");
        String classpath = System.getProperty("java.class.path");
        return new ProcessBuilder(java, "--enable-preview", "-cp", classpath, IndexLockProcessMain.class.getName(), mode, database.toString()).start();
    }

    private static Void holdRead(Path database, CountDownLatch acquired, CountDownLatch release) throws Exception {
        try (var lock = IndexLock.read(database, Duration.ofSeconds(1))) {
            assertTrue(lock.isHeld());
            acquired.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release same-JVM readers");
            }
        }
        return null;
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameJvmReadersOverlapAndReleaseTheSharedOperatingSystemLock() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.jsonl");
        var acquired = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> holdRead(database, acquired, release));
            var second = executor.submit(() -> holdRead(database, acquired, release));
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> {
                try (var unexpected = IndexLock.write(database, Duration.ofMillis(100))) {
                    assertTrue(unexpected.isHeld(), "writer acquired while readers held the shared lock");
                    throw new AssertionError("writer acquired while readers held the shared lock");
                }
            });
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
        try (var writer = IndexLock.write(database, Duration.ofSeconds(1))) {
            assertTrue(writer.isHeld());
        }
    }

    @Test
    void twoReaderProcessesOverlapBeforeEitherBlocksAWritingProcess() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.jsonl");
        Process first = process("hold-read", database);
        Process second = process("hold-read", database);
        try (var firstOutput = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8));
             var secondOutput = new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", firstOutput.readLine());
            assertEquals("locked", secondOutput.readLine());
            assertThrows(IOException.class, () -> {
                try (var unexpected = IndexLock.write(database, Duration.ofMillis(100))) {
                    assertTrue(unexpected.isHeld(), "writer acquired while reader processes held the shared lock");
                    throw new AssertionError("writer acquired while reader processes held the shared lock");
                }
            });
            first.getOutputStream().close();
            second.getOutputStream().close();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertTrue(second.waitFor(5, TimeUnit.SECONDS));
        } finally {
            stop(first);
            stop(second);
        }
    }

    @Test
    void aRealReaderProcessBlocksAnExclusiveWriterUntilItExits() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.jsonl");
        Process process = process("hold-read", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", reader.readLine());
            try (var lock = IndexLock.write(database, Duration.ofMillis(150))) {
                assertTrue(lock.isHeld(), "exclusive writer acquired while reader process held its shared lock");
                throw new AssertionError("exclusive writer acquired while reader process held its shared lock");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("exclusive database lock"));
            }

            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            try (var lock = IndexLock.write(database, Duration.ofSeconds(1))) {
                assertTrue(lock.isHeld());
                assertTrue(Files.exists(database.resolveSibling("symbols.jsonl.lock")));
            }
        } finally {
            stop(process);
        }
    }

    @Test
    void writerUsesOneDeadlineAcrossLocalAndOperatingSystemContention() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.jsonl");
        Process externalReader = process("hold-read", database);
        var localReaderAcquired = new CountDownLatch(1);
        var releaseLocalReader = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        try (var output = new BufferedReader(new InputStreamReader(externalReader.getInputStream(), StandardCharsets.UTF_8));
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            assertEquals("locked", output.readLine());
            var localReader = executor.submit(() -> holdRead(database, localReaderAcquired, releaseLocalReader));
            assertTrue(localReaderAcquired.await(2, TimeUnit.SECONDS));
            long startedAt = System.nanoTime();
            var writer = executor.submit(() -> {
                writerStarted.countDown();
                return assertThrows(IOException.class, () -> {
                    try (var unexpected = IndexLock.write(database, Duration.ofMillis(300))) {
                        assertTrue(unexpected.isHeld());
                        throw new AssertionError("writer acquired while external reader held the operating-system lock");
                    }
                });
            });
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
            scheduler.schedule(releaseLocalReader::countDown, 180, TimeUnit.MILLISECONDS);

            IOException timeout = writer.get(2, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(timeout.getMessage().contains("after 300 milliseconds"));
            assertTrue(elapsedMillis < 430, "separate local and OS budgets took " + elapsedMillis + " milliseconds");
            localReader.get(2, TimeUnit.SECONDS);
        } finally {
            releaseLocalReader.countDown();
            externalReader.getOutputStream().close();
            stop(externalReader);
        }
    }

    @Test
    void aRealQueryProcessReleasesAllWindowsHandlesBeforeRename() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.jsonl");
        SymbolIndexFixture.write(database, new MinecraftVersion("1.21.5"), Path.of("client"), "a".repeat(64), Instant.EPOCH, List.of(SymbolIndexFixture.minecraftPackage(1, "marker")), List.of(SymbolIndexFixture.minecraftType(1, 1, "marker.Marker", "Marker", "class", null, List.of(), "marker/Marker.java", 1, 1)), List.of(), List.of(), List.of());
        Process process = process("query-and-close", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("closed", reader.readLine());
            Path renamed = temporaryDirectory.resolve("renamed.jsonl");
            Files.move(database, renamed);
            assertTrue(Files.exists(renamed));
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        } finally {
            stop(process);
        }
    }

    @Test
    void aRealRecoveryProcessRestoresTheBackupWhenTheTargetIsMissing() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.jsonl");
        Path backup = database.resolveSibling("symbols.jsonl.bak");
        Files.writeString(database, "old");
        Files.move(database, backup);

        Process process = process("recover-failing", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("recovered", reader.readLine());
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertTrue(Files.exists(database));
            assertFalse(Files.exists(backup));
        } finally {
            stop(process);
        }
    }
}
