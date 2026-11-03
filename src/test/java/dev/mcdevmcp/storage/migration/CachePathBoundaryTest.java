package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CachePathBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void selectedCacheBelowAncestorAliasUsesPinnedPhysicalRoot() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        Path alias = temporary.resolve("alias");
        Path sentinel = Files.writeString(temporary.resolve("outside-sentinel"), "outside");
        DirectoryAliasFixture.create(alias, real);
        try {
            PlatformPaths logical = new PlatformPaths(alias.resolve("cache"));
            CachePathBoundary boundary = CachePathBoundary.open(logical);
            assertEquals(real.resolve("cache").toRealPath(), boundary.physicalRoot());
            Path source = Files.createDirectory(boundary.resolve(logical.cacheRoot().resolve("source")));
            Files.writeString(source.resolve("marker"), "inside");
            assertEquals(SourceTreeInventory.capture(source, Cancellation.none()), SourceTreeInventory.capture(logical.cacheRoot().resolve("source"), boundary, Cancellation.none()));
            assertThrows(IOException.class, () -> SourceTreeInventory.capture(logical.cacheRoot().resolve("source"), Cancellation.none()));
            boundary.require(logical);
            boundary.require(boundary.resolvedPaths());
            assertEquals("outside", Files.readString(sentinel));
        } finally {
            DirectoryAliasFixture.remove(alias);
        }
        assertEquals("outside", Files.readString(sentinel));
    }

    @Test
    void boundaryAliasInternalLinkAndOutsidePathsAreRejected() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        Path alias = temporary.resolve("alias");
        DirectoryAliasFixture.create(alias, real);
        try {
            assertThrows(IOException.class, () -> CachePathBoundary.open(new PlatformPaths(alias)));
        } finally {
            DirectoryAliasFixture.remove(alias);
        }
        CachePathBoundary boundary = CachePathBoundary.open(new PlatformPaths(real.resolve("cache")));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "outside");
        Path internal = boundary.physicalRoot().resolve("internal");
        DirectoryAliasFixture.create(internal, outside);
        try {
            assertThrows(IOException.class, () -> boundary.require(internal));
            assertThrows(IOException.class, () -> boundary.require(internal.resolve("sentinel")));
            assertThrows(IOException.class, () -> boundary.require(sentinel));
            assertThrows(IOException.class, () -> SourceTreeInventory.capture(internal.resolve("sentinel"), boundary, Cancellation.none()));
            assertThrows(IOException.class, () -> boundary.require(new PlatformPaths(outside)));
            assertEquals("outside", Files.readString(sentinel));
        } finally {
            DirectoryAliasFixture.remove(internal);
        }
    }

    @Test
    void ancestorRetargetingDoesNotRetargetExistingBoundary() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Path alias = temporary.resolve("alias");
        DirectoryAliasFixture.create(alias, first);
        try {
            PlatformPaths selected = new PlatformPaths(alias.resolve("cache"));
            CachePathBoundary pinned = CachePathBoundary.open(selected);
            Files.writeString(pinned.resolve(selected.cacheRoot().resolve("marker")), "first");
            DirectoryAliasFixture.remove(alias);
            DirectoryAliasFixture.create(alias, second);
            CachePathBoundary later = CachePathBoundary.open(selected);
            Files.writeString(later.resolve(selected.cacheRoot().resolve("marker")), "second");
            assertEquals("first", Files.readString(pinned.resolve(selected.cacheRoot().resolve("marker"))));
            assertEquals("second", Files.readString(later.resolve(selected.cacheRoot().resolve("marker"))));
            assertNotEquals(pinned.physicalRoot(), later.physicalRoot());
        } finally {
            DirectoryAliasFixture.remove(alias);
        }
    }

    @Test
    void replacingPinnedRootIsDetectedBeforeChildAccess() throws Exception {
        Path root = temporary.resolve("cache");
        CachePathBoundary boundary = CachePathBoundary.open(new PlatformPaths(root));
        Files.writeString(root.resolve("original"), "retained original");
        Path retained = temporary.resolve("retained");
        Files.move(root, retained);
        Files.createDirectory(root);
        assertThrows(IOException.class, boundary::revalidate);
        assertThrows(IOException.class, () -> boundary.resolve(root.resolve("new")));
        assertFalse(Files.exists(root.resolve("new")));
        assertEquals("retained original", Files.readString(retained.resolve("original")));
    }
}