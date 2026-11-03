package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SourceIndexTransactionPublisherTest {
    @TempDir
    Path temporary;

    private static int runProcess(SourcePublicationFixture fixture, String event) throws Exception {
        try (Process process = new ProcessBuilder(System.getProperty("mcdevMcpJava"), "--enable-preview", "-cp", System.getProperty("java.class.path"), SourcePublicationProcessMain.class.getName(), fixture.paths().cacheRoot().toString(), fixture.transaction().toString(), event).redirectErrorStream(true).redirectOutput(fixture.transaction().resolve("process-" + event + ".log").toFile()).start()) {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Migration process did not terminate: " + event);
            }
            return process.exitValue();
        }
    }

    private static SourcePublicationResult publish(SourceIndexTransactionPublisher publisher, SourcePublicationFixture fixture, VersionOperationLease lease, SourceIndexSnapshot before, Cancellation cancellation) throws IOException {
        return publisher.publish(fixture.paths(), fixture.version(), lease, fixture.transaction(), fixture.source(), fixture.database(), fixture.stamp(), before, cancellation);
    }

    private static Path pending(SourcePublicationFixture fixture) {
        return fixture.transaction().getParent().resolve("pending.json");
    }

    @ParameterizedTest
    @EnumSource(SourcePublicationPhase.class)
    void interruptedPhaseRecoversExactOldOrCommittedPair(SourcePublicationPhase phase) throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        SourceIndexTransactionPublisher stable = new SourceIndexTransactionPublisher();
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = stable.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            SourceTreeInventory candidate = SourceTreeInventory.capture(fixture.source(), Cancellation.none());
            var interrupted = new SourceIndexTransactionPublisher((event, _) -> {
                if (event.equals(phase.name())) throw new SourcePublicationCrash();
            });
            assertThrows(SourcePublicationCrash.class, () -> publish(interrupted, fixture, lease, before, Cancellation.none()));
            assertTrue(Files.exists(pending(fixture)));
            stable.recover(fixture.paths(), fixture.version(), lease);
            assertFalse(Files.exists(pending(fixture)));
            if (phase == SourcePublicationPhase.COMMITTED) {
                assertEquals(candidate, SourceTreeInventory.capture(fixture.paths().sourceRoot(fixture.version()), Cancellation.none()));
            }
            else {
                assertEquals(before, stable.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
            }
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
            assertEquals(before.index(), SourceTreeInventory.capture(fixture.transaction().resolve("old/index"), Cancellation.none()));
            stable.recover(fixture.paths(), fixture.version(), lease);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SourcePublicationPhase.class, names = "COMMITTED", mode = EnumSource.Mode.EXCLUDE)
    void cancellationAtEveryPrecommitPhaseRestoresValidOldDatabase(SourcePublicationPhase phase) throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, false);
        var cancelled = new AtomicBoolean();
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals(phase.name())) cancelled.set(true);
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            assertThrows(IOException.class, () -> publish(publisher, fixture, lease, before, cancelled::get));
            assertEquals(before, publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
            assertFalse(Files.exists(pending(fixture)));
        }
    }

    @Test
    void commitsAndRetainsAllOriginalBytes() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, false);
        var publisher = new SourceIndexTransactionPublisher();
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            SourcePublicationResult result = publish(publisher, fixture, lease, before, Cancellation.none());
            assertEquals(fixture.transaction().toAbsolutePath(), result.retainedMigration());
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("held/client"), Cancellation.none()));
            assertEquals(before.index(), SourceTreeInventory.capture(fixture.transaction().resolve("old/index"), Cancellation.none()));
            assertEquals("legacy index bytes", Files.readString(fixture.paths().indexRoot(fixture.version()).resolve("minecraft/legacy.json")));
            assertFalse(Files.exists(pending(fixture)));
        }
    }

    @Test
    void conflictingInstallationTargetIsPreservedAndBlocksRecovery() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var injected = new AtomicBoolean();
        var publisher = new SourceIndexTransactionPublisher((event, path) -> {
            if (event.equals("BEFORE_MOVE") && path.equals(fixture.paths().sourceRoot(fixture.version())) && injected.compareAndSet(false, true)) {
                Files.createDirectory(path);
                Files.writeString(path.resolve("external.txt"), "external conflict");
            }
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            IOException failure = assertThrows(IOException.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            assertTrue(failure.getMessage().contains("recovery required"));
            assertEquals("external conflict", Files.readString(fixture.paths().sourceRoot(fixture.version()).resolve("external.txt")));
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
            assertTrue(Files.exists(pending(fixture)));
            assertThrows(IOException.class, () -> new SourceIndexTransactionPublisher().recover(fixture.paths(), fixture.version(), lease));
        }
        assertThrows(IOException.class, () -> {
            try (var unexpected = VersionOperationLease.read(fixture.paths(), fixture.version())) {
                unexpected.require(fixture.paths(), fixture.version());
            }
        });
    }

    @Test
    void detectsChangesDuringStagingBeforeSnapshotOrMutation() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var publisher = new SourceIndexTransactionPublisher();
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            Files.createDirectory(fixture.paths().sourceRoot(fixture.version()).resolve("user-added-empty"));
            assertThrows(IOException.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            assertTrue(Files.exists(fixture.paths().sourceRoot(fixture.version()).resolve("user-added-empty")));
            assertFalse(Files.exists(pending(fixture)));
        }
    }

    @Test
    void reusePublishesIndexAndStampWithoutMovingSourceDirectory() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        Path source = fixture.paths().sourceRoot(fixture.version());
        Object originalKey = Files.readAttributes(source, BasicFileAttributes.class).fileKey();
        SourcePreparationStamp staged = SourceProvenance.read(fixture.stamp()).orElseThrow();
        Files.delete(fixture.stamp());
        SourceProvenance.write(fixture.stamp(), new SourcePreparationStamp(1, SourceOwnership.VALIDATED_EXTERNAL, null, staged.inputs(), SourceTreeInventory.capture(source, Cancellation.none()), new SourceValidation(SourceValidationStatus.VALID, List.of(), List.of("Old.java"), List.of("Old.java"))));
        var publisher = new SourceIndexTransactionPublisher((event, path) -> {
            if (event.equals("BEFORE_MOVE") && (path.equals(source) || path.equals(fixture.transaction().resolve("held/client")))) {
                fail("Reused source was moved");
            }
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            publisher.publish(fixture.paths(), fixture.version(), lease, fixture.transaction(), source, fixture.database(), fixture.stamp(), before, SourcePublicationMode.REUSE_SOURCES, Cancellation.none());
            assertEquals(before.source(), SourceTreeInventory.capture(source, Cancellation.none()));
            assertEquals(originalKey, Files.readAttributes(source, BasicFileAttributes.class).fileKey());
            assertFalse(Files.exists(fixture.transaction().resolve("held/client")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrupt-journal", "missing-backup", "altered-backup"})
    void uncertainRecoveryLeavesPendingAndOriginalArtifacts(String fault) throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals("BACKED_UP")) throw new SourcePublicationCrash();
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            assertThrows(SourcePublicationCrash.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            switch (fault) {
                case "corrupt-journal" -> Files.writeString(pending(fixture), "{");
                case "missing-backup" -> Files.delete(fixture.transaction().resolve("old/client/Old.java"));
                case "altered-backup" ->
                        Files.writeString(fixture.transaction().resolve("old/client/Old.java"), "altered");
                default -> throw new AssertionError(fault);
            }
            assertThrows(IOException.class, () -> new SourceIndexTransactionPublisher().recover(fixture.paths(), fixture.version(), lease));
            assertTrue(Files.exists(pending(fixture)));
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("held/client"), Cancellation.none()));
            assertTrue(Files.exists(fixture.source()));
        }
        assertThrows(IOException.class, () -> {
            try (var unexpected = VersionOperationLease.read(fixture.paths(), fixture.version())) {
                unexpected.require(fixture.paths(), fixture.version());
            }
        });
    }

    @Test
    void rollbackClearsInterruptionTemporarilyAndRestoresItAfterward() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals("INSTALLING_DATABASE")) Thread.currentThread().interrupt();
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            boolean interruptionRestored;
            try {
                assertThrows(IOException.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            } finally {
                interruptionRestored = Thread.interrupted();
            }
            assertTrue(interruptionRestored);
            assertEquals(before, publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
        }
    }

    @Test
    void lateCancellationReturnsCommittedResult() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var cancelled = new AtomicBoolean();
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals("COMMITTED")) cancelled.set(true);
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            SourcePublicationResult result = publish(publisher, fixture, lease, before, cancelled::get);
            assertEquals(result.sourceInventory(), SourceTreeInventory.capture(fixture.paths().sourceRoot(fixture.version()), Cancellation.none()));
            assertFalse(Files.exists(pending(fixture)));
        }
    }

    @Test
    void committedTransactionRecordSurvivesStalePendingPointer() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var previousPointer = new AtomicReference<byte[]>();
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals("VALIDATING_PAIR")) previousPointer.set(Files.readAllBytes(pending(fixture)));
            if (event.equals("COMMITTED")) {
                Files.write(pending(fixture), previousPointer.get());
                throw new SourcePublicationCrash();
            }
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            SourceTreeInventory candidate = SourceTreeInventory.capture(fixture.source(), Cancellation.none());
            assertThrows(SourcePublicationCrash.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            new SourceIndexTransactionPublisher().recover(fixture.paths(), fixture.version(), lease);
            assertEquals(candidate, SourceTreeInventory.capture(fixture.paths().sourceRoot(fixture.version()), Cancellation.none()));
            assertTrue(Files.isRegularFile(fixture.transaction().resolve("committed.json")));
            assertFalse(Files.exists(pending(fixture)));
        }
    }

    @Test
    void failureAfterCompletedMoveRestoresFromRetainedCopies() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var injected = new AtomicBoolean();
        var publisher = new SourceIndexTransactionPublisher((event, path) -> {
            if (event.equals("AFTER_MOVE") && path.equals(fixture.paths().sourceRoot(fixture.version())) && injected.compareAndSet(false, true)) {
                throw new IOException("Provider reported uncertain completed move");
            }
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            assertThrows(IOException.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            assertEquals(before, publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
            try (var failed = Files.list(fixture.transaction().resolve("failed"))) {
                assertTrue(failed.anyMatch(path -> Files.isRegularFile(path.resolve("New.java"))));
            }
        }
    }

    @Test
    void rollbackFailureKeepsPendingUntilSuccessfulRetry() throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var publisher = new SourceIndexTransactionPublisher((event, path) -> {
            if (event.equals("INSTALLING_DATABASE")) throw new IOException("Injected installation failure");
            if (event.equals("BEFORE_MOVE") && path.getParent().equals(fixture.transaction().resolve("failed"))) {
                throw new IOException("Injected rollback failure");
            }
        });
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            SourceIndexSnapshot before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
            IOException failure = assertThrows(IOException.class, () -> publish(publisher, fixture, lease, before, Cancellation.none()));
            assertTrue(failure.getMessage().contains("recovery required"));
            assertTrue(Files.exists(pending(fixture)));
            assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
            new SourceIndexTransactionPublisher().recover(fixture.paths(), fixture.version(), lease);
            assertEquals(before, publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
        }
    }

    @Test
    void ordinaryMoveWithExistingTargetPreservesBothObjects() throws Exception {
        Path source = Files.writeString(temporary.resolve("source"), "source bytes");
        Path target = Files.writeString(temporary.resolve("target"), "target bytes");
        assertThrows(IOException.class, () -> Files.move(source, target));
        assertEquals("source bytes", Files.readString(source));
        assertEquals("target bytes", Files.readString(target));
    }

    @ParameterizedTest
    @EnumSource(SourcePublicationPhase.class)
    void freshProcessRecoversAfterProcessTerminationAtEveryPhase(SourcePublicationPhase phase) throws Exception {
        verifyProcessRecovery(phase.name(), phase == SourcePublicationPhase.COMMITTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void freshProcessRecoversAfterMoveBeforePhaseAdvance(int move) throws Exception {
        verifyProcessRecovery("MOVE_" + move, false);
    }

    private void verifyProcessRecovery(String event, boolean committed) throws Exception {
        SourcePublicationFixture fixture = SourcePublicationFixture.create(temporary, true);
        var publisher = new SourceIndexTransactionPublisher();
        SourceIndexSnapshot before;
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            before = publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none());
        }
        SourceTreeInventory candidate = SourceTreeInventory.capture(fixture.source(), Cancellation.none());
        assertEquals(73, runProcess(fixture, event));
        assertTrue(Files.exists(pending(fixture)));
        assertThrows(IOException.class, () -> {
            try (var unexpected = VersionOperationLease.read(fixture.paths(), fixture.version())) {
                unexpected.require(fixture.paths(), fixture.version());
            }
        });
        assertEquals(0, runProcess(fixture, "RECOVER"));
        try (var lease = VersionOperationLease.write(fixture.paths(), fixture.version())) {
            if (committed) {
                assertEquals(candidate, publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()).source());
            }
            else {
                assertEquals(before, publisher.captureBefore(fixture.paths(), fixture.version(), lease, Cancellation.none()));
            }
        }
        assertEquals(before.source(), SourceTreeInventory.capture(fixture.transaction().resolve("old/client"), Cancellation.none()));
        assertEquals(before.index(), SourceTreeInventory.capture(fixture.transaction().resolve("old/index"), Cancellation.none()));
    }
}