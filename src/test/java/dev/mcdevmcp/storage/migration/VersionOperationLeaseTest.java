package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.symbols.VersionStateRepository;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class VersionOperationLeaseTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.11");
    @TempDir
    Path temporaryDirectory;

    @Test
    void tokenChecksRootVersionOwnerAndClosedState() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        VersionOperationLease lease = VersionOperationLease.read(paths, VERSION);
        try (lease; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lease.require(paths, VERSION);
            assertFalse(lease.isWrite());
            assertEquals(VERSION, lease.version());
            assertThrows(IOException.class, () -> lease.require(new PlatformPaths(temporaryDirectory.resolve("other")), VERSION));
            assertThrows(IllegalStateException.class, () -> lease.require(paths, new MinecraftVersion("26.1")));
            assertTrue(executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> lease.require(paths, VERSION));
                return true;
            }).get(5, TimeUnit.SECONDS));
        }
        assertThrows(IllegalStateException.class, () -> lease.require(paths, VERSION));
    }

    @Test
    void pendingBlocksReadStateAndCleanupAndFailedReadReleasesLock() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        Path source = paths.sourceRoot(VERSION).resolve("Original.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Original {}");
        Path pending = paths.cacheRoot().resolve("migrations").resolve(VERSION.value()).resolve("pending.json");
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "corrupt pointer still blocks reads");
        RecoveryRequiredException failure = assertThrows(RecoveryRequiredException.class, () -> {
            try (var unexpected = VersionOperationLease.read(paths, VERSION)) {
                unexpected.require(paths, VERSION);
            }
        });
        assertTrue(failure.getMessage().contains(pending.toString()));
        assertThrows(RecoveryRequiredException.class, () -> new VersionStateRepository(paths).state(VERSION));
        CacheCleaner cleaner = new CacheCleaner(paths);
        assertThrows(RecoveryRequiredException.class, () -> cleaner.clean(VERSION));
        assertThrows(RecoveryRequiredException.class, () -> cleaner.cleanAll(VERSION));
        assertThrows(RecoveryRequiredException.class, () -> cleaner.cleanCache(VERSION));
        try (var lease = VersionOperationLease.write(paths, VERSION)) {
            assertTrue(lease.isWrite());
            assertEquals(VersionState.SOURCE_ONLY, new VersionStateRepository(paths).state(VERSION, lease));
            assertThrows(RecoveryRequiredException.class, () -> lease.requireNoPending(paths, VERSION));
        }
        assertEquals("class Original {}", Files.readString(source));
        assertTrue(Files.exists(pending));
    }

    @Test
    void versionDiscoveryRetainsPendingVersionWhenCanonicalPairIsAbsent() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        Path pending = paths.cacheRoot().resolve("migrations").resolve(VERSION.value()).resolve("pending.json");
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "{}");
        assertEquals(List.of(VERSION), new CacheCleaner(paths).cachedVersions());
    }

    @Test
    void ancestorRetargetKeepsHeldLeaseAndCleanerOnThePinnedPhysicalCache() throws Exception {
        Path firstParent = Files.createDirectories(temporaryDirectory.resolve("first"));
        Path secondParent = Files.createDirectories(temporaryDirectory.resolve("second"));
        PlatformPaths first = new PlatformPaths(Files.createDirectories(firstParent.resolve("cache-root")));
        PlatformPaths second = new PlatformPaths(Files.createDirectories(secondParent.resolve("cache-root")));
        Path firstSource = first.sourceRoot(VERSION).resolve("Old.java");
        Path secondSource = second.sourceRoot(VERSION).resolve("New.java");
        Files.createDirectories(firstSource.getParent());
        Files.createDirectories(secondSource.getParent());
        Files.writeString(firstSource, "old");
        Files.writeString(secondSource, "outside sentinel");
        Path alias = temporaryDirectory.resolve("selected-parent");
        DirectoryAliasFixture.create(alias, firstParent);
        PlatformPaths selected = new PlatformPaths(alias.resolve("cache-root"));
        try {
            CachePathBoundary pinned;
            try (var lease = VersionOperationLease.read(selected, VERSION)) {
                pinned = lease.boundary();
                assertEquals(first.cacheRoot().toRealPath(), lease.resolvedPaths().cacheRoot());
                DirectoryAliasFixture.remove(alias);
                DirectoryAliasFixture.create(alias, secondParent);
                lease.require(selected, VERSION);
                lease.require(first, VERSION);
                assertThrows(IOException.class, () -> lease.require(second, VERSION));
                assertEquals("old", Files.readString(lease.boundary().require(selected.sourceRoot(VERSION).resolve("Old.java"))));
                try (var next = VersionOperationLease.write(selected, VERSION)) {
                    assertEquals(second.cacheRoot().toRealPath(), next.resolvedPaths().cacheRoot());
                }
            }
            new CacheCleaner(pinned).cleanCache(VERSION);
            assertFalse(Files.exists(firstSource));
            assertEquals("outside sentinel", Files.readString(secondSource));
        } finally {
            DirectoryAliasFixture.remove(alias);
        }
    }

    @Test
    void anotherProcessHoldsReadLeaseUntilItsWholeOperationEnds() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        Process process = new ProcessBuilder(System.getProperty("mcdevMcpJava"), "--enable-preview", "-cp", System.getProperty("java.class.path"), VersionOperationLeaseProcessMain.class.getName(), temporaryDirectory.toString(), VERSION.value()).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var output = process.inputReader()) {
            try {
                assertEquals("read-held", executor.submit(output::readLine).get(10, TimeUnit.SECONDS));
                var started = new CountDownLatch(1);
                var writer = executor.submit(() -> {
                    started.countDown();
                    try (var lease = VersionOperationLease.write(paths, VERSION)) {
                        lease.require(paths, VERSION);
                        return lease.isWrite();
                    }
                });
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> writer.get(150, TimeUnit.MILLISECONDS));
                } finally {
                    process.getOutputStream().close();
                }
                assertTrue(writer.get(10, TimeUnit.SECONDS));
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
