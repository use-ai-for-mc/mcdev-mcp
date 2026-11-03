package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourcePublicationBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void publicationThroughAncestorAliasStaysOnPinnedRootWhenAliasRetargets() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        SourcePublicationFixture fixture = SourcePublicationFixture.create(real, true);
        Path other = Files.createDirectories(temporary.resolve("other/cache/client"));
        Path sentinel = Files.writeString(other.resolve("outside-sentinel"), "outside cache bytes");
        Path alias = temporary.resolve("alias");
        DirectoryAliasFixture.create(alias, real);
        try {
            PlatformPaths logical = new PlatformPaths(alias.resolve("cache"));
            var publisher = new SourceIndexTransactionPublisher((event, _) -> {
                if (event.equals("PREPARED")) {
                    try {
                        DirectoryAliasFixture.remove(alias);
                        DirectoryAliasFixture.create(alias, temporary.resolve("other"));
                    } catch (Exception exception) {
                        throw new IOException("Alias fixture retarget failed", exception);
                    }
                }
            });
            try (var lease = VersionOperationLease.write(logical, fixture.version())) {
                SourceIndexSnapshot before = publisher.captureBefore(logical, fixture.version(), lease, Cancellation.none());
                Path logicalTransaction = alias.resolve(real.relativize(fixture.transaction()));
                SourcePublicationResult result = publisher.publish(logical, fixture.version(), lease, logicalTransaction, alias.resolve(real.relativize(fixture.source())), alias.resolve(real.relativize(fixture.database())), alias.resolve(real.relativize(fixture.stamp())), before, Cancellation.none());
                assertEquals(fixture.transaction(), result.retainedMigration());
                assertEquals(result.sourceInventory(), SourceTreeInventory.capture(fixture.paths().sourceRoot(fixture.version()), Cancellation.none()));
                assertTrue(Files.isRegularFile(fixture.paths().sourceRoot(fixture.version()).resolve("New.java")));
                assertEquals("outside cache bytes", Files.readString(sentinel));
                assertFalse(Files.exists(other.resolve("New.java")));
                publisher.recover(logical, fixture.version(), lease);
            }
        } finally {
            DirectoryAliasFixture.remove(alias);
        }
        assertEquals("outside cache bytes", Files.readString(sentinel));
    }

    @Test
    void internalJunctionAtInstallFailsClosedAndRetainsOriginals() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "outside bytes");
        Path canonicalSource = fixture.paths().sourceRoot(fixture.version());
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals("INSTALLING_SOURCE")) {
                try {
                    DirectoryAliasFixture.create(canonicalSource, outside);
                } catch (Exception exception) {
                    throw new IOException("Internal junction fixture failed", exception);
                }
            }
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            try {
                IOException failure = assertThrows(IOException.class, () -> publisher.publish(fixture.paths(), fixture.version(), lease, fixture.transaction(), fixture.source(), fixture.database(), fixture.stamp(), before, Cancellation.none()));
                assertTrue(failure.getMessage().contains("recovery required"));
                assertTrue(Files.isRegularFile(fixture.transaction().getParent().resolve("pending.json")));
                assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
                assertEquals("outside bytes", Files.readString(sentinel));
            } finally {
                DirectoryAliasFixture.remove(canonicalSource);
            }
            var stable = new SourceIndexTransactionPublisher();
            stable.recover(fixture.paths(), fixture.version(), lease);
            assertEquals(before, stable.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
        }
        assertEquals("outside bytes", Files.readString(sentinel));
    }
}
