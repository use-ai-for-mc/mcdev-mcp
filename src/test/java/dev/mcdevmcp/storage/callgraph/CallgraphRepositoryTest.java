package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleLock;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CallgraphRepositoryTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    @TempDir
    Path temporaryDirectory;

    private static List<CallgraphDataRecord> fixtureRecords() {
        List<CallgraphDataRecord> records = new ArrayList<>();
        records.add(edge(1, "caller.Beta", "run", "(I)V", "target.Target", "hit", "(I)V", 7));
        records.add(edge(2, "caller.Alpha", "entry", null, "target.Target", "hit", "()V", null));
        records.add(edge(3, "caller.Alpha", "entry", "", "target.Target", "hit", "(I)V", 0));
        records.add(edge(4, "caller.Alpha", "entry", "()V", "target.Target", "hit", "()V", 11));
        records.add(edge(5, "caller.Alpha", "entry", "()V", "target.Target", "hit", "()V", 11));
        records.add(edge(6, "origin.Origin", "dispatch", "()V", "callee.Overload", "work", "()V", 7));
        records.add(edge(7, "origin.Origin", "dispatch", "(I)V", "callee.Overload", "work", null, null));
        records.add(edge(8, "origin.Origin", "dispatch", "()V", "callee.Empty", "none", "", -4));
        return List.copyOf(records);
    }

    private static CallgraphDataRecord edge(long id, String callerClass, String callerMethod, String callerDescriptor, String calleeClass, String calleeMethod, String calleeDescriptor, Integer line) {
        return new CallgraphDataRecord(id, callerClass, callerMethod, callerDescriptor, calleeClass, calleeMethod, calleeDescriptor, line);
    }

    private static Path generation(Path bundle) throws Exception {
        CallgraphPointer pointer = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(bundle.resolve("current.json")), CallgraphPointer.class);
        return bundle.resolve("generations").resolve(pointer.generation());
    }

    private static java.util.Set<String> generationNames(Path bundle) throws IOException {
        try (var generations = Files.list(bundle.resolve("generations"))) {
            return generations.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    @Test
    void methodReferenceDisplaysCanonicalDescriptors() {
        assertEquals("sample.Target.run(I)V", new MethodReference("sample.Target", "run", "(I)V", 7, 1).displayName());
    }

    @Test
    void validatesExactBundleAndLegacyNullableDescriptors() throws Exception {
        Path bundle = fixture();

        CallgraphBundleValidator.validate(bundle);
        var repository = new CallgraphRepository(bundle, VERSION);
        List<MethodReference> callers = repository.callers("target.Target", "hit", 20);

        assertEquals(java.util.Arrays.asList(null, "", "()V", "()V", "(I)V"), callers.stream().map(MethodReference::descriptor).toList());
        assertEquals(java.util.Arrays.asList(null, 0, 11, 11, 7), callers.stream().map(MethodReference::lineNumber).toList());
        assertEquals(List.of(2L, 3L, 4L, 5L, 1L), callers.stream().map(MethodReference::edgeId).toList());
        assertEquals("caller.Alpha.entry", callers.getFirst().displayName());
        assertEquals("caller.Alpha.entry", callers.get(1).displayName());
        assertEquals("caller.Alpha.entry()V", callers.get(2).displayName());
    }

    @Test
    void aggregatesOverloadsPreservesDuplicatesAndQueriesExactlyTheRequestedBound() throws Exception {
        var repository = new CallgraphRepository(fixture());

        List<MethodReference> callers = repository.callers("target.Target", "hit", 3);
        assertEquals(List.of(2L, 3L, 4L), callers.stream().map(MethodReference::edgeId).toList());

        List<MethodReference> callees = repository.callees("origin.Origin", "dispatch", 20);
        assertEquals(List.of("callee.Empty.none", "callee.Overload.work", "callee.Overload.work()V"), callees.stream().map(MethodReference::displayName).toList());
        assertEquals(List.of(8L, 7L, 6L), callees.stream().map(MethodReference::edgeId).toList());
        assertTrue(repository.callers("target.Target", "absent", 1).isEmpty());
    }

    @Test
    void holdsTheCommonSharedLockForEachRead() throws Exception {
        Path bundle = fixture();
        try (var writeLock = BundleLock.write(bundle, Duration.ofSeconds(1));
             var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(writeLock.isHeld());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> executor.submit(() -> new CallgraphRepository(bundle, null, Duration.ofMillis(50)).callers("target.Target", "hit", 1)).get());
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("Timed out acquiring shared bundle lock"));
        }
    }

    @Test
    void forcedMultiPassSortIsDeterministicAndUsesExactJsonlSchema() throws Exception {
        List<CallgraphDataRecord> records = fixtureRecords();
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");

        String firstIdentity = CallgraphBundleTestSupport.publish(first, VERSION, records, 1, 2);
        String secondIdentity = CallgraphBundleTestSupport.publish(second, VERSION, records.reversed(), 1, 2);

        assertEquals(firstIdentity, secondIdentity);
        assertEquals(Files.readString(generation(first).resolve("callers.jsonl")), Files.readString(generation(second).resolve("callers.jsonl")));
        String data = Files.readString(generation(first).resolve("callers.jsonl"));
        String index = Files.readString(generation(first).resolve("callers.index.jsonl"));
        assertFalse(data.contains("\r"));
        assertTrue(data.endsWith("\n"));
        assertTrue(data.lines().findFirst().orElseThrow().startsWith("{\"edgeId\":"));
        assertTrue(index.lines().findFirst().orElseThrow().startsWith("{\"className\":\"callee.Empty\",\"methodName\":\"none\",\"byteOffset\":0,\"byteLength\":"));
        String manifest = Files.readString(generation(first).resolve("manifest.json"));
        assertTrue(manifest.contains("\"artifact\":\"CALLERS_DATA\""));
        assertFalse(manifest.contains("\"path\":"));
        CallgraphBundleValidator.validate(first);
    }

    @Test
    void detectsPointerGenerationChangesAndLazilyRefreshesIndexes() throws Exception {
        Path bundle = fixture();
        var repository = new CallgraphRepository(bundle);
        assertEquals(5, repository.callers("target.Target", "hit", 20).size());

        CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(edge(1, "fresh.Caller", "run", "()V", "fresh.Target", "hit", "()V", 42)));

        assertTrue(repository.callers("target.Target", "hit", 20).isEmpty());
        assertEquals(List.of("fresh.Caller.run()V"), repository.callers("fresh.Target", "hit", 20).stream().map(MethodReference::displayName).toList());
    }

    @Test
    void indexedSeeksResolveFirstMiddleLastMissingAndUtf8Keys() throws Exception {
        Path bundle = temporaryDirectory.resolve("seeks");
        CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(edge(1, "caller.First", "run", "()V", "alpha.Target", "hit", "()V", 1), edge(2, "вызывающий.Middle", "run", "()V", "middle.Target", "hit", "()V", 2), edge(3, "caller.Last", "run", "()V", "zulu.Target", "hit", "()V", 3)));
        var repository = new CallgraphRepository(bundle);

        assertEquals("caller.First.run()V", repository.callers("alpha.Target", "hit", 1).getFirst().displayName());
        assertEquals("вызывающий.Middle.run()V", repository.callers("middle.Target", "hit", 1).getFirst().displayName());
        assertEquals("caller.Last.run()V", repository.callers("zulu.Target", "hit", 1).getFirst().displayName());
        assertTrue(repository.callers("missing.Target", "hit", 1).isEmpty());
        CallgraphBundleValidator.validate(bundle);
    }

    @Test
    void generatedWriterRejectsLegacyDescriptorsAtItsBoundary() throws Exception {
        Path bundle = temporaryDirectory.resolve("generated-validation");
        try (var writer = new CallgraphBundleWriter(bundle, VERSION, "0".repeat(64), dev.mcdevmcp.support.Cancellation.none())) {
            assertThrows(NullPointerException.class, () -> writer.accept(edge(1, "caller.First", "run", null, "target.Target", "hit", "()V", 1)));
            assertThrows(IllegalArgumentException.class, () -> writer.accept(edge(2, "caller.First", "run", "", "target.Target", "hit", "()V", 1)));
        }
        assertFalse(CallgraphRepository.isPublished(bundle));
    }

    @Test
    void publicationCleansOlderGenerationsBeforeActivatingTheNextOne() throws Exception {
        Path bundle = temporaryDirectory.resolve("generation-cleanup");
        String first = CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(edge(1, "one.Caller", "run", "()V", "one.Target", "hit", "()V", 1)));
        String second = CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(edge(1, "two.Caller", "run", "()V", "two.Target", "hit", "()V", 2)));
        assertEquals(2, generationNames(bundle).size());

        String third = CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(edge(1, "three.Caller", "run", "()V", "three.Target", "hit", "()V", 3)));

        assertEquals(java.util.Set.of(second, third), generationNames(bundle));
        assertFalse(Files.exists(bundle.resolve("generations").resolve(first)));
    }

    @Test
    void rejectsASymbolicLinkForTheGenerationsDirectoryWithoutTouchingItsTarget() throws Exception {
        Path bundle = temporaryDirectory.resolve("symlinked-generations");
        Path outside = temporaryDirectory.resolve("outside-generations");
        Path sentinel = outside.resolve("a".repeat(64)).resolve("sentinel.txt");
        Files.createDirectories(bundle);
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "keep");
        try {
            Files.createSymbolicLink(bundle.resolve("generations"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
            return;
        }

        assertThrows(IOException.class, () -> {
            try (var writer = new CallgraphBundleWriter(bundle, VERSION, "0".repeat(64), dev.mcdevmcp.support.Cancellation.none())) {
                writer.publish(0, 0, 0);
            }
        });
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test
    void cleansCrashOrphanedStagingWithoutDeletingAConcurrentWritersStaging() throws Exception {
        Path bundle = temporaryDirectory.resolve("staging-recovery");
        Files.createDirectories(bundle);
        String orphanName = ".staging-" + java.util.UUID.randomUUID();
        Path orphan = bundle.resolve(orphanName);
        Path orphanMarker = bundle.resolve(orphanName + ".lock");
        Files.createDirectories(orphan.resolve("work"));
        Files.writeString(orphan.resolve("work/run.jsonl"), "orphan");
        Files.write(orphanMarker, new byte[0]);

        Path activeStaging;
        try (var active = new CallgraphBundleWriter(bundle, VERSION, "0".repeat(64), dev.mcdevmcp.support.Cancellation.none())) {
            active.accept(edge(1, "active.Caller", "run", "()V", "active.Target", "hit", "()V", 1));
            try (var children = Files.list(bundle)) {
                activeStaging = children.filter(path -> path.getFileName().toString().startsWith(".staging-")).filter(path -> !path.getFileName().toString().endsWith(".lock")).filter(path -> !path.equals(orphan)).findFirst().orElseThrow();
            }

            CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(edge(1, "fresh.Caller", "run", "()V", "fresh.Target", "hit", "()V", 1)));

            assertFalse(Files.exists(orphan));
            assertFalse(Files.exists(orphanMarker));
            assertTrue(Files.isDirectory(activeStaging));
            assertTrue(Files.isRegularFile(bundle.resolve(activeStaging.getFileName() + ".lock")));
        }
        assertFalse(Files.exists(activeStaging));
        assertFalse(Files.exists(bundle.resolve(activeStaging.getFileName() + ".lock")));
    }

    @Test
    void treatsCorruptPresentPointersAsPublishedSoQueriesReportCorruption() throws Exception {
        Path bundle = temporaryDirectory.resolve("corrupt-present-pointer");
        Files.createDirectories(bundle.resolve("current.json"));

        assertTrue(CallgraphRepository.isPublished(bundle));
        assertThrows(IOException.class, () -> new CallgraphRepository(bundle).callers("x.Y", "z", 1));
    }

    @Test
    void publicationStatusValidatesPublishedArtifactContentAndPresence() throws Exception {
        Path alteredBundle = fixture();
        assertEquals(CallgraphRepository.PublicationStatus.PUBLISHED, CallgraphRepository.publicationStatus(alteredBundle));
        Path callers = generation(alteredBundle).resolve("callers.jsonl");
        byte[] altered = Files.readAllBytes(callers);
        altered[0] ^= 1;
        Files.write(callers, altered);

        assertEquals(CallgraphRepository.PublicationStatus.CORRUPT, CallgraphRepository.publicationStatus(alteredBundle));
        assertTrue(CallgraphRepository.isPublished(alteredBundle));

        Path missingBundle = fixture();
        Files.delete(generation(missingBundle).resolve("callees.jsonl"));

        assertEquals(CallgraphRepository.PublicationStatus.CORRUPT, CallgraphRepository.publicationStatus(missingBundle));
        assertTrue(CallgraphRepository.isPublished(missingBundle));
    }

    @Test
    void rejectsCorruptionAndUnsafePointerState() throws Exception {
        Path bundle = fixture();
        Path generation = generation(bundle);
        Path callers = generation.resolve("callers.jsonl");
        byte[] bytes = Files.readAllBytes(callers);
        bytes[0] ^= 1;
        Files.write(callers, bytes);

        IOException validationFailure = assertThrows(IOException.class, () -> CallgraphBundleValidator.validate(bundle));
        assertTrue(validationFailure.getMessage().contains("SHA-256"));

        Files.writeString(bundle.resolve("current.json"), "{\"format\":\"bad\",\"schemaVersion\":1,\"generation\":\"../unsafe\"}");
        assertThrows(IOException.class, () -> new CallgraphRepository(bundle).callers("x.Y", "z", 1));
    }

    @Test
    void pointerReplacementFailurePreservesThePriorPublishedGeneration() throws Exception {
        Path bundle = fixture();
        String priorPointer = Files.readString(bundle.resolve("current.json"));

        IOException failure;
        try (var writer = new CallgraphBundleWriter(bundle, VERSION, "0".repeat(64), dev.mcdevmcp.support.Cancellation.none(), 1, 2, (_, _) -> {
            throw new IOException("injected pointer replacement failure");
        })) {
            writer.accept(edge(1, "fresh.Caller", "run", "()V", "fresh.Target", "hit", "()V", 1));
            failure = assertThrows(IOException.class, () -> writer.publish(1, 1, 1));
        }

        assertEquals("injected pointer replacement failure", failure.getMessage());
        assertEquals(priorPointer, Files.readString(bundle.resolve("current.json")));
        CallgraphBundleValidator.validate(bundle);
    }

    @Test
    void emptyGraphProducesValidEmptyJsonlArtifacts() throws Exception {
        Path bundle = temporaryDirectory.resolve("empty");
        CallgraphBundleTestSupport.publish(bundle, VERSION, List.of());

        CallgraphBundleValidator.validate(bundle);
        assertEquals(0, Files.size(generation(bundle).resolve("callers.jsonl")));
        assertEquals(0, Files.size(generation(bundle).resolve("callers.index.jsonl")));
        assertTrue(new CallgraphRepository(bundle).callers("x.Y", "z", 1).isEmpty());
    }

    private Path fixture() throws Exception {
        Path bundle = temporaryDirectory.resolve("fixture-" + System.nanoTime());
        CallgraphBundleTestSupport.publish(bundle, VERSION, fixtureRecords());
        return bundle;
    }
}
