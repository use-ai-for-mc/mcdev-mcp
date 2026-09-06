package dev.mcdevmcp.analysis.callgraph;

import dev.mcdevmcp.storage.callgraph.*;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CallgraphScannerIntegrationTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    @TempDir
    Path temporaryDirectory;

    private static CallgraphRequest request(Path jar, Path bundle, int threads, Cancellation cancellation) {
        return new CallgraphRequest(VERSION, jar, bundle, threads, (_, _, _) -> {
        }, cancellation);
    }

    private static CallEdge call(String callerClass, String callerMethod, String calleeMethod, long encounterOrder) {
        return new CallEdge(callerClass, callerMethod, "()V", "target.Target", calleeMethod, "()V", null, encounterOrder);
    }

    private static void createPrior(Path bundle) throws Exception {
        CallgraphBundleTestSupport.publish(bundle, VERSION, List.of(new CallgraphDataRecord(1, "prior.Caller", "run", null, "prior.Target", "hit", "", -7)));
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static int replaceAll(byte[] target, byte[] source, byte[] replacement) {
        if (source.length != replacement.length) {
            throw new IllegalArgumentException("ZIP entry replacements must have equal lengths");
        }
        int replacements = 0;
        for (int offset = 0; offset <= target.length - source.length; offset++) {
            int index = 0;
            while (index < source.length && target[offset + index] == source[index]) {
                index++;
            }
            if (index == source.length) {
                System.arraycopy(replacement, 0, target, offset, replacement.length);
                replacements++;
                offset += source.length - 1;
            }
        }
        return replacements;
    }

    private static String current(Path bundle) throws IOException {
        return Files.readString(bundle.resolve("current.json"), StandardCharsets.UTF_8);
    }

    private static List<String> snapshot(Path bundle) throws Exception {
        CallgraphPointer pointer = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(bundle.resolve("current.json")), CallgraphPointer.class);
        Path generation = bundle.resolve("generations").resolve(pointer.generation());
        List<String> snapshot = new ArrayList<>();
        try (var files = Files.list(generation)) {
            for (Path file : files.sorted().toList()) {
                snapshot.add(file.getFileName() + "=" + Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        snapshot.add("current=" + current(bundle));
        return List.copyOf(snapshot);
    }

    @Test
    void validatesAndNormalizesRequests() {
        Path relativeJar = Path.of("fixture.jar");
        Path relativeBundle = Path.of("callgraph");
        var request = request(relativeJar, relativeBundle, 1, Cancellation.none());

        assertTrue(request.remappedJar().isAbsolute());
        assertTrue(request.outputBundle().isAbsolute());
        assertThrows(IllegalArgumentException.class, () -> request(relativeJar, relativeBundle, 0, Cancellation.none()));
        assertThrows(NullPointerException.class, () -> new CallgraphRequest(null, relativeJar, relativeBundle, 1, (_, _, _) -> {
        }, Cancellation.none()));
        assertEquals(2, CallgraphScanner.parserWindow(1));
        assertEquals(8, CallgraphScanner.parserWindow(4));
        assertEquals(256, CallgraphScanner.parserWindow(10_000));
    }

    @Test
    void producesTheSameValidatedBundleWithOneAndFourWorkers() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory.resolve("fixture"));
        Path serial = temporaryDirectory.resolve("serial/callgraph");
        Path parallel = temporaryDirectory.resolve("parallel/callgraph");

        CallgraphSummary serialSummary = new CallgraphScanner().scan(request(fixture.jar(), serial, 1, Cancellation.none()));
        CallgraphSummary parallelSummary = new CallgraphScanner().scan(request(fixture.jar(), parallel, 4, Cancellation.none()));

        assertEquals(fixture.classBytes().size(), serialSummary.classes());
        assertEquals(serialSummary.classes(), parallelSummary.classes());
        assertEquals(serialSummary.methods(), parallelSummary.methods());
        assertEquals(serialSummary.edges(), parallelSummary.edges());
        assertTrue(serialSummary.edges() > 0);
        assertEquals(snapshot(serial), snapshot(parallel));
        CallgraphBundleValidator.validate(serial);
        CallgraphBundleValidator.validate(parallel);
    }

    @Test
    void cancellationParserFailureAndWriterFailureLeaveThePriorBundleIntact() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory.resolve("fixture"));

        Path cancelled = temporaryDirectory.resolve("cancelled/callgraph");
        createPrior(cancelled);
        String prior = current(cancelled);
        assertThrows(IOException.class, () -> new CallgraphScanner().scan(request(fixture.jar(), cancelled, 2, () -> true)));
        assertTrue(Thread.interrupted(), "cancellation must preserve interruption");
        assertEquals(prior, current(cancelled));

        Path cancellationDuringBuild = temporaryDirectory.resolve("cancelled-during-build/callgraph");
        createPrior(cancellationDuringBuild);
        AtomicBoolean cancelAfterFirstBatch = new AtomicBoolean();
        CallgraphRequest cancellationRequest = new CallgraphRequest(VERSION, fixture.jar(), cancellationDuringBuild, 1, (_, percent, _) -> {
            if (percent > 5) {
                cancelAfterFirstBatch.set(true);
            }
        }, cancelAfterFirstBatch::get);
        assertThrows(Exception.class, () -> new CallgraphScanner().scan(cancellationRequest));
        assertTrue(Thread.interrupted(), "mid-build cancellation must preserve interruption");
        assertEquals(prior, current(cancellationDuringBuild));

        Path malformedJar = temporaryDirectory.resolve("malformed.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(malformedJar))) {
            writeEntry(output, "broken/Broken.class", new byte[]{0, 1, 2, 3});
        }
        Path parserFailure = temporaryDirectory.resolve("parser/callgraph");
        createPrior(parserFailure);
        assertThrows(Exception.class, () -> new CallgraphScanner().scan(request(malformedJar, parserFailure, 2, Cancellation.none())));
        assertEquals(prior, current(parserFailure));

        Path writerFailure = temporaryDirectory.resolve("writer/callgraph");
        createPrior(writerFailure);
        var failingWriter = new CallgraphWriter(() -> {
            throw new IOException("injected writer validation failure");
        });
        assertThrows(IOException.class, () -> new CallgraphScanner(failingWriter).scan(request(fixture.jar(), writerFailure, 2, Cancellation.none())));
        assertEquals(prior, current(writerFailure));
    }

    @Test
    void keepsThePriorGenerationQueryableUntilPublication() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory.resolve("query-during-build-fixture"));
        Path bundle = temporaryDirectory.resolve("query-during-build/callgraph");
        createPrior(bundle);
        var observed = new AtomicReference<List<MethodReference>>();
        var writer = new CallgraphWriter(() -> observed.set(new CallgraphRepository(bundle).callers("prior.Target", "hit", 2)));

        new CallgraphScanner(writer).scan(request(fixture.jar(), bundle, 2, Cancellation.none()));

        assertEquals(List.of("prior.Caller.run"), observed.get().stream().map(MethodReference::displayName).toList());
    }

    @Test
    void rejectsOversizedExpandedClassEntriesWithoutReplacingThePriorBundle() throws Exception {
        Path jar = temporaryDirectory.resolve("oversized-class.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "oversized/Huge.class", new byte[CallgraphScanner.MAXIMUM_CLASS_BYTES + 1]);
        }
        Path bundle = temporaryDirectory.resolve("oversized-class/callgraph");
        createPrior(bundle);
        String prior = current(bundle);

        IOException failure = assertThrows(IOException.class, () -> new CallgraphScanner().scan(request(jar, bundle, 2, Cancellation.none())));

        assertTrue(failure.getMessage().contains("expanded-byte limit"));
        assertEquals(prior, current(bundle));
    }

    @Test
    void rejectsDuplicateEntryAndBinaryClassNamesWithoutReplacingThePriorBundle() throws Exception {
        var fixture = CallgraphTestSupport.compile(temporaryDirectory.resolve("fixture"));
        Path bundle = temporaryDirectory.resolve("duplicates/callgraph");
        createPrior(bundle);
        String prior = current(bundle);

        IOException entryFailure = assertThrows(IOException.class, () -> new CallgraphScanner().scan(request(duplicateEntryNamesJar(), bundle, 2, Cancellation.none())));
        assertEquals("Duplicate class entry in remapped JAR: duplicate/A1.class", entryFailure.getMessage());
        assertEquals(prior, current(bundle));

        IOException binaryFailure = assertThrows(IOException.class, () -> new CallgraphScanner().scan(request(duplicateBinaryClassesJar(fixture.bytes("callgraph.fixture.Fixture")), bundle, 2, Cancellation.none())));
        assertEquals("Duplicate binary class callgraph.fixture.Fixture in remapped JAR entries alpha/One.class and omega/Two.class", binaryFailure.getMessage());
        assertEquals(prior, current(bundle));
    }

    @Test
    void translatesPerClassEncounterOrderIntoStableGlobalIdsWhileDraining() throws Exception {
        Path bundle = temporaryDirectory.resolve("encounter-order/callgraph");
        Path jar = temporaryDirectory.resolve("empty.jar");
        Files.write(jar, new byte[0]);
        var batches = new ArrayDeque<>(List.of(new InvocationExtractor.Extraction("alpha.First", 1, List.of(call("alpha.First", "one", "first", 0), call("alpha.First", "one", "second", 1))), new InvocationExtractor.Extraction("beta.Second", 1, List.of(call("beta.Second", "two", "third", 0)))));

        CallgraphWriter.Counts counts = new CallgraphWriter().write(request(jar, bundle, 1, Cancellation.none()), batches::pollFirst);

        assertEquals(new CallgraphWriter.Counts(2, 2, 3), counts);
        var repository = new CallgraphRepository(bundle);
        assertEquals(List.of(1L, 2L), repository.callees("alpha.First", "one", 10).stream().map(MethodReference::edgeId).toList());
        assertEquals(List.of(3L), repository.callees("beta.Second", "two", 10).stream().map(MethodReference::edgeId).toList());
    }

    @Test
    void rejectsNonSequentialEncounterOrderWithoutReplacingThePriorBundle() throws Exception {
        Path bundle = temporaryDirectory.resolve("invalid-encounter-order/callgraph");
        Path jar = temporaryDirectory.resolve("unused.jar");
        Files.write(jar, new byte[0]);
        createPrior(bundle);
        String prior = current(bundle);
        var batches = new ArrayDeque<>(List.of(new InvocationExtractor.Extraction("alpha.First", 1, List.of(call("alpha.First", "one", "first", 1)))));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new CallgraphWriter().write(request(jar, bundle, 1, Cancellation.none()), batches::pollFirst));

        assertEquals("Non-sequential encounter order for alpha.First: expected 0, found 1", failure.getMessage());
        assertEquals(prior, current(bundle));
    }

    private Path duplicateEntryNamesJar() throws IOException {
        String first = "duplicate/A1.class";
        String second = "duplicate/B2.class";
        var bytes = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(bytes)) {
            writeEntry(output, first, new byte[]{0});
            writeEntry(output, second, new byte[]{1});
        }
        byte[] archive = bytes.toByteArray();
        assertEquals(2, replaceAll(archive, second.getBytes(StandardCharsets.US_ASCII), first.getBytes(StandardCharsets.US_ASCII)));
        Path jar = temporaryDirectory.resolve("duplicate-entries.jar");
        Files.write(jar, archive);
        return jar;
    }

    private Path duplicateBinaryClassesJar(byte[] classBytes) throws IOException {
        Path jar = temporaryDirectory.resolve("duplicate-classes.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "alpha/One.class", classBytes);
            writeEntry(output, "omega/Two.class", classBytes);
        }
        return jar;
    }
}
