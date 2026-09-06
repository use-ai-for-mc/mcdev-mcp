package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.storage.h2.AtomicH2Database;
import dev.mcdevmcp.storage.h2.DatabaseValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve", "SqlWithoutWhere"})
class SymbolIndexWriterValidationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsWrongMemberIdentityAndMetadataWhilePreservingPriorDatabase() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("writer/source"));
        Files.writeString(sources.resolve("A.java"), "class A { int value; }", StandardCharsets.UTF_8);
        Files.writeString(sources.resolve("B.java"), "class B {}", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("writer-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("writer.mv.db");
        IndexRequest request = IndexerTestSupport.request(sources, jar, database, 1);
        new SourceIndexer().build(request);
        byte[] original = IndexerTestSupport.bytes(database);

        List<DatabaseValidator> corruptions = List.of(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE fields SET type_id = 2 WHERE id = 1");
            }
        }, connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE metadata SET minecraft_version = 'wrong-version'");
            }
        });
        for (DatabaseValidator corruption : corruptions) {
            SymbolIndexWriter writer = new SymbolIndexWriter(new AtomicH2Database(), corruption);
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
        Path database = temporaryDirectory.resolve("stale-backup.mv.db");
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
