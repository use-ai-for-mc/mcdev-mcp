package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.storage.symbols.AtomicSymbolIndex;
import dev.mcdevmcp.storage.symbols.IndexValidator;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SymbolIndexWriterValidationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsWrongMemberIdentityAndMetadataWhilePreservingPriorDatabase() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("writer/source"));
        Files.writeString(sources.resolve("A.java"), "class A { int value; }", StandardCharsets.UTF_8);
        Files.writeString(sources.resolve("B.java"), "class B {}", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("writer-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("writer.jsonl");
        IndexRequest request = IndexerTestSupport.request(sources, jar, database, 1);
        new SourceIndexer().build(request);
        byte[] original = IndexerTestSupport.bytes(database);

        List<IndexValidator> corruptions = List.of(file -> SymbolIndexFixture.rewrite(file, data -> {
            SymbolIndexData.FieldRow field = data.fields().getFirst();
            return new SymbolIndexData(data.header(), data.packages(), data.types(), List.of(new SymbolIndexData.FieldRow(field.id(), 2, field.ordinal(), field.name(), field.type(), field.modifiers(), field.startOffset(), field.endOffset(), field.startLine(), field.endLine())), data.methods(), data.parameters());
        }), file -> SymbolIndexFixture.rewrite(file, data -> SymbolIndexFixture.withHeader(data, header -> new SymbolIndexData.Header(header.format(), "wrong-version", header.sourceRoot(), header.remappedJarSha256(), header.builtAt(), header.packages(), header.types(), header.fields(), header.methods(), header.parameters()))));
        for (IndexValidator corruption : corruptions) {
            SymbolIndexWriter writer = new SymbolIndexWriter(new AtomicSymbolIndex(), corruption);
            assertThrows(IndexBuildException.class, () -> new SourceIndexPipeline(new JavacSourceParser(), writer).build(request));
            assertArrayEquals(original, IndexerTestSupport.bytes(database));
        }
    }

    @Test
    void rebuildsExactSnapshotWhenValidPriorTargetHasStaleBackup() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("stale-backup/source"));
        Path source = sources.resolve("Current.java");
        Files.writeString(source, "class Prior {}", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("stale-backup-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("stale-backup.jsonl");
        IndexRequest request = IndexerTestSupport.request(sources, jar, database, 1);
        new SourceIndexer().build(request);
        Path backup = database.resolveSibling(database.getFileName() + ".bak");
        Files.copy(database, backup);
        Files.writeString(source, "class Current { long changed; }", StandardCharsets.UTF_8);

        new SourceIndexer().build(request);

        List<String> dump = IndexerTestSupport.dump(database);
        assertFalse(Files.exists(backup));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|Current|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("fields|") && row.contains("|changed|long|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|Prior|")));
    }
}
