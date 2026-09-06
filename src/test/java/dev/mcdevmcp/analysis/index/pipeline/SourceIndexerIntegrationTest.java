package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SourceIndexerIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private static void assertMovable(Path path) throws Exception {
        Path moved = path.resolveSibling(path.getFileName() + ".moved");
        Files.move(path, moved);
        Files.move(moved, path);
    }

    private static IndexRequest withProgress(IndexRequest base, List<String> progress) {
        return new IndexRequest(base.minecraftVersion(), base.sourceRoots(), base.remappedJar(), base.classpath(), base.outputDatabase(), base.threads(), (_, _, message) -> progress.add(message), base.cancellation());
    }

    @Test
    void threadCountsOneAndFourProduceIdenticalOrderedTables() throws Exception {
        Path sources = IndexerTestSupport.copyFixture("main", temporaryDirectory.resolve("sources"));
        Path jar = IndexerTestSupport.fixtureCatalog(temporaryDirectory.resolve("remapped.jar"));
        Path dependency = IndexerTestSupport.fixtureDependency(temporaryDirectory.resolve("dependency.jar"));
        Path one = temporaryDirectory.resolve("one.mv.db");
        Path four = temporaryDirectory.resolve("four.mv.db");

        List<SourceRoot> roots = List.of(new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, Optional.empty(), sources));
        IndexSummary oneSummary = new SourceIndexer().build(IndexerTestSupport.request(roots, jar, List.of(dependency), one, 1));
        IndexSummary fourSummary = new SourceIndexer().build(IndexerTestSupport.request(roots, jar, List.of(dependency), four, 4));

        assertEquals(oneSummary.packages(), fourSummary.packages());
        assertEquals(oneSummary.types(), fourSummary.types());
        assertEquals(oneSummary.fields(), fourSummary.fields());
        assertEquals(oneSummary.methods(), fourSummary.methods());
        assertEquals(oneSummary.parameters(), fourSummary.parameters());
        assertEquals(IndexerTestSupport.dump(one), IndexerTestSupport.dump(four));
    }

    @Test
    void persistsTypedMinecraftAndFabricSourceIdentities() throws Exception {
        Path minecraft = Files.createDirectories(temporaryDirectory.resolve("minecraft/shared"));
        Files.writeString(minecraft.resolve("MinecraftType.java"), "package shared; public class MinecraftType {}", StandardCharsets.UTF_8);
        Path fabric = Files.createDirectories(temporaryDirectory.resolve("fabric/shared"));
        Files.writeString(fabric.resolve("FabricType.java"), "package shared; public class FabricType {}", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("identities.mv.db");
        List<SourceRoot> roots = List.of(new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, Optional.empty(), minecraft.getParent()), new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.FABRIC, Optional.of(new dev.mcdevmcp.storage.model.FabricApiVersion("0.120.0")), fabric.getParent()));

        new SourceIndexer().build(IndexerTestSupport.request(roots, jar, List.of(), database, 2));
        List<String> dump = IndexerTestSupport.dump(database);

        assertTrue(dump.stream().anyMatch(row -> row.startsWith("metadata|true|1|1.21.5|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("packages|") && row.contains("|minecraft|null|shared")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("packages|") && row.contains("|fabric|0.120.0|shared")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|minecraft|null|shared.MinecraftType|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|fabric|0.120.0|shared.FabricType|")));
    }

    @Test
    void doesNotFollowLinkedSourceDirectories() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root/real"));
        Files.writeString(root.resolve("Real.java"), "package real; public class Real {}", StandardCharsets.UTF_8);
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside/linked"));
        Files.writeString(outside.resolve("Linked.java"), "package linked; public class Linked {}", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.getParent().resolve("linked"), outside.getParent());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + exception.getMessage());
        }
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("nofollow.mv.db");

        new SourceIndexer().build(IndexerTestSupport.request(root.getParent(), jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);

        assertTrue(dump.stream().anyMatch(row -> row.contains("|real.Real|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|linked.Linked|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|real/Real.java|")));
    }

    @Test
    void packageAndModuleUnitsProduceNoTypeRows() throws Exception {
        Path sources = IndexerTestSupport.copyFixture("modules", temporaryDirectory.resolve("modules"));
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());
        Path oneDatabase = temporaryDirectory.resolve("modules-one.mv.db");
        Path fourDatabase = temporaryDirectory.resolve("modules-four.mv.db");

        IndexSummary one = new SourceIndexer().build(IndexerTestSupport.request(sources, jar, oneDatabase, 1));
        IndexSummary four = new SourceIndexer().build(IndexerTestSupport.request(sources, jar, fourDatabase, 4));

        assertEquals(one.evidence().discoveredCompilationUnits(), one.evidence().parsedCompilationUnits());
        assertEquals(List.of("index/module/package-info.java", "module-info.java"), one.evidence().typeFreeCompilationUnits());
        assertEquals(one.evidence(), four.evidence());

        assertEquals(0, one.types());
        assertEquals(0, four.types());
        assertEquals(IndexerTestSupport.dump(oneDatabase), IndexerTestSupport.dump(fourDatabase));
        assertTrue(IndexerTestSupport.dump(oneDatabase).stream().noneMatch(row -> row.startsWith("types|")));
    }

    @Test
    void modularClasspathReadabilityFailureIsIndependentOfThreadCount() throws Exception {
        Path priorSources = Files.createDirectories(temporaryDirectory.resolve("modular-prior/prior"));
        Files.writeString(priorSources.resolve("Prior.java"), "package prior; public class Prior {}", StandardCharsets.UTF_8);
        Path modularSources = Files.createDirectories(temporaryDirectory.resolve("modular-sources"));
        Files.writeString(modularSources.resolve("module-info.java"), "module modular.index {}", StandardCharsets.UTF_8);
        Path modularPackage = Files.createDirectories(modularSources.resolve("modular"));
        Files.writeString(modularPackage.resolve("UsesDependency.java"), "package modular; public class UsesDependency { dependency.External value; }", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.fixtureDependency(temporaryDirectory.resolve("modular-dependency.jar"));
        Path oneDatabase = temporaryDirectory.resolve("modular-one.mv.db");
        Path fourDatabase = temporaryDirectory.resolve("modular-four.mv.db");
        new SourceIndexer().build(IndexerTestSupport.request(priorSources.getParent(), jar, oneDatabase, 1));
        new SourceIndexer().build(IndexerTestSupport.request(priorSources.getParent(), jar, fourDatabase, 1));
        byte[] oneOriginal = IndexerTestSupport.bytes(oneDatabase);
        byte[] fourOriginal = IndexerTestSupport.bytes(fourDatabase);

        IndexBuildException oneFailure = assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(IndexerTestSupport.request(modularSources, jar, oneDatabase, 1)));
        assertEquals("Unable to resolve stored semantic type: dependency.External", oneFailure.getMessage());
        assertArrayEquals(oneOriginal, IndexerTestSupport.bytes(oneDatabase));
        IndexBuildException fourFailure = assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(IndexerTestSupport.request(modularSources, jar, fourDatabase, 4)));

        assertEquals(oneFailure.getMessage(), fourFailure.getMessage());
        assertArrayEquals(fourOriginal, IndexerTestSupport.bytes(fourDatabase));
    }

    @Test
    void emptySourceCorpusProducesEmptyIndexWithoutStartingWorkers() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("empty-sources"));
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty-corpus.jar"), Map.of());
        Path database = temporaryDirectory.resolve("empty-corpus.mv.db");

        IndexSummary summary = new SourceIndexer().build(IndexerTestSupport.request(sources, jar, database, 4));

        assertEquals(0, summary.packages());
        assertEquals(0, summary.types());
        assertTrue(IndexerTestSupport.dump(database).stream().noneMatch(row -> row.startsWith("types|")));
    }

    @Test
    void allowsOnlyAnUnrelatedMethodBodyAttributionError() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("body/body"));
        Files.writeString(sources.resolve("Allowed.java"), "package body; public class Allowed { java.util.List<String> values; int value() { return missingBodyName; } }", StandardCharsets.UTF_8);
        Path root = sources.getParent();
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("body.mv.db");
        var progress = new java.util.ArrayList<String>();
        IndexRequest base = IndexerTestSupport.request(root, jar, database, 1);
        IndexRequest request = new IndexRequest(base.minecraftVersion(), base.sourceRoots(), base.remappedJar(), base.classpath(), base.outputDatabase(), base.threads(), (_, _, message) -> progress.add(message), base.cancellation());

        IndexSummary summary = new SourceIndexer().build(request);

        assertEquals(1, summary.types());
        assertTrue(progress.stream().anyMatch(message -> message.contains("missingBodyName")));
    }

    @Test
    void onDemandBodyDiagnosticsAreIdenticalWithOneAndFourThreads() throws Exception {
        Path root = temporaryDirectory.resolve("on-demand-body");
        Path entry = Files.createDirectories(root.resolve("entry"));
        Path dependency = Files.createDirectories(root.resolve("dependency"));
        Files.writeString(entry.resolve("Entry.java"), "package entry; public class Entry { dependency.Dependency value; }", StandardCharsets.UTF_8);
        Files.writeString(dependency.resolve("Dependency.java"), "package dependency; public class Dependency { int broken() { return missingBodyName; } }", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("on-demand-body-empty.jar"), Map.of());
        var oneProgress = new java.util.ArrayList<String>();
        var fourProgress = new java.util.ArrayList<String>();
        Path oneDatabase = temporaryDirectory.resolve("on-demand-one.mv.db");
        Path fourDatabase = temporaryDirectory.resolve("on-demand-four.mv.db");

        IndexSummary one = new SourceIndexer().build(withProgress(IndexerTestSupport.request(root, jar, oneDatabase, 1), oneProgress));
        IndexSummary four = new SourceIndexer().build(withProgress(IndexerTestSupport.request(root, jar, fourDatabase, 4), fourProgress));

        assertEquals(one.packages(), four.packages());
        assertEquals(one.types(), four.types());
        assertEquals(one.fields(), four.fields());
        assertEquals(one.methods(), four.methods());
        assertEquals(one.parameters(), four.parameters());
        assertEquals(oneProgress.stream().filter(message -> message.contains("compiler.err")).toList(), fourProgress.stream().filter(message -> message.contains("compiler.err")).toList());
        assertEquals(1, oneProgress.stream().filter(message -> message.contains("missingBodyName")).count());
        assertEquals(IndexerTestSupport.dump(oneDatabase), IndexerTestSupport.dump(fourDatabase));
    }

    @Test
    void indexesOnDemandSecondaryTopLevelDeclarationsExactlyOnce() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("secondary/demand"));
        Files.writeString(root.resolve("Entry.java"), "package demand; public class Entry extends Secondary {}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Types.java"), "package demand; class Primary {} class Secondary extends Primary {}", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("secondary-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("secondary.mv.db");

        IndexSummary summary = new SourceIndexer().build(IndexerTestSupport.request(root.getParent(), jar, database, 4));
        List<String> dump = IndexerTestSupport.dump(database);

        assertEquals(3, summary.types());
        assertEquals(1, dump.stream().filter(row -> row.startsWith("types|") && row.split("\\|", -1)[5].equals("demand.Secondary")).count());
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|demand.Entry|") && row.contains("|demand.Secondary|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|demand.Secondary|") && row.contains("|demand.Primary|")));
    }

    @Test
    void rejectsUnresolvedStoredTypesAndAmbiguousImports() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());
        for (var source : List.of("package bad; class Missing { UnknownType value; }", "package bad; import java.util.*; import java.sql.*; class Ambiguous { Date value; }")) {
            Path root = Files.createDirectories(temporaryDirectory.resolve("bad-" + Integer.toUnsignedString(source.hashCode())));
            Files.writeString(root.resolve("Bad.java"), source, StandardCharsets.UTF_8);
            IndexBuildException failure = assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(IndexerTestSupport.request(root, jar, root.resolve("symbols.mv.db"), 1)));
            assertTrue(failure.getMessage().contains("diagnostic") || failure.getMessage().contains("resolve"), failure.getMessage());
        }
    }

    @Test
    void rejectsUnresolvedAndAmbiguousHierarchyClauses() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("hierarchy-errors-empty.jar"), Map.of());
        List<Map<String, String>> fixtures = List.of(Map.of("bad/Bad.java", "package bad; class Bad extends Missing {}"), Map.of("bad/Bad.java", "package bad; class Bad implements Missing {}"), Map.of("one/Clash.java", "package one; public class Clash {}", "two/Clash.java", "package two; public class Clash {}", "bad/Bad.java", "package bad; import one.*; import two.*; class Bad extends Clash {}"));
        int fixtureIndex = 0;
        for (Map<String, String> fixture : fixtures) {
            Path root = temporaryDirectory.resolve("hierarchy-error-" + fixtureIndex++);
            for (var source : fixture.entrySet()) {
                Path file = root.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            }
            IndexBuildException failure = assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(IndexerTestSupport.request(root, jar, root.resolve("symbols.mv.db"), 4)));
            assertTrue(failure.getMessage().contains("diagnostic") || failure.getMessage().contains("resolve"), failure.getMessage());
        }
    }

    @Test
    void releasesCompilerArchivesAndDatabaseAfterSuccessAndFailure() throws Exception {
        Path sources = IndexerTestSupport.copyFixture("main", temporaryDirectory.resolve("sources"));
        Path jar = IndexerTestSupport.fixtureCatalog(temporaryDirectory.resolve("remapped.jar"));
        Path dependency = IndexerTestSupport.fixtureDependency(temporaryDirectory.resolve("dependency.jar"));
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        IndexRequest request = IndexerTestSupport.request(List.of(new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, Optional.empty(), sources)), jar, List.of(dependency), database, 4);

        new SourceIndexer().build(request);
        assertMovable(jar);
        assertMovable(dependency);
        assertMovable(database);

        Path broken = Files.createDirectories(temporaryDirectory.resolve("broken"));
        Files.writeString(broken.resolve("Broken.java"), "class Broken { Missing stored; }", StandardCharsets.UTF_8);
        assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(IndexerTestSupport.request(List.of(new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, Optional.empty(), broken)), jar, List.of(dependency), database, 4)));
        assertMovable(jar);
        assertMovable(dependency);
        assertMovable(database);
    }
}
