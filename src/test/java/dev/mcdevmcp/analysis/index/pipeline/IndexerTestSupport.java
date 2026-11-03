package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class IndexerTestSupport {
    private IndexerTestSupport() {
    }

    static IndexRequest request(Path sourceRoot, Path remappedJar, Path outputIndex, int threads) {
        return request(List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), sourceRoot)), remappedJar, List.of(), outputIndex, threads);
    }

    static IndexRequest request(List<SourceRoot> sourceRoots, Path remappedJar, List<Path> classpath, Path outputIndex, int threads) {
        return new IndexRequest(new MinecraftVersion("1.21.5"), sourceRoots, remappedJar, classpath, outputIndex, threads, (_, _, _) -> {
        }, Cancellation.none());
    }

    static Path copyFixture(String name, Path target) throws Exception {
        URI resource = Objects.requireNonNull(IndexerTestSupport.class.getResource("/indexer/sources/" + name), "Missing indexer fixture " + name).toURI();
        Path source = Path.of(resource);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                }
                else {
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    static Path createJar(Path jar, Map<String, String> sources) throws Exception {
        Path workspace = Files.createDirectories(jar.resolveSibling(jar.getFileName() + ".sources"));
        Path classes = Files.createDirectories(jar.resolveSibling(jar.getFileName() + ".classes"));
        List<Path> sourceFiles = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            Path source = workspace.resolve(entry.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(source);
        }
        if (!sourceFiles.isEmpty()) {
            var compiler = ToolProvider.getSystemJavaCompiler();
            try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                var units = files.getJavaFileObjectsFromPaths(sourceFiles);
                boolean compiled = compiler.getTask(null, files, null, List.of("--release", "25", "-proc:none", "-d", classes.toString()), null, units).call();
                assertTrue(compiled, "test catalog sources must compile");
            }
        }
        Files.createDirectories(jar.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (Files.exists(classes)) {
                try (var paths = Files.walk(classes)) {
                    for (Path classFile : paths.filter(Files::isRegularFile).sorted().toList()) {
                        String entryName = classes.relativize(classFile).toString().replace('\\', '/');
                        output.putNextEntry(new JarEntry(entryName));
                        Files.copy(classFile, output);
                        output.closeEntry();
                    }
                }
            }
        }
        return jar;
    }

    static Path fixtureCatalog(Path jar) throws Exception {
        return createJar(jar, Map.of("index/fixture/FeatureSet.java", "package index.fixture; public class FeatureSet extends java.util.ArrayList<String> implements Runnable { public void run() {} }"));
    }

    static Path fixtureDependency(Path jar) throws Exception {
        return createJar(jar, Map.of("dependency/External.java", "package dependency; public final class External {}"));
    }

    static List<String> dump(Path database) throws Exception {
        SymbolIndexData data = SymbolIndexFiles.read(database);
        SymbolIndexData.Header header = data.header();
        List<String> rows = new ArrayList<>();
        rows.add(row("metadata", true, header.format(), header.minecraftVersion(), header.sourceRoot(), header.remappedJarSha256()));
        for (var value : data.packages()) {
            rows.add(row("packages", value.id(), value.namespace(), value.fabricApiVersion(), value.name()));
        }
        for (var value : data.types()) {
            rows.add(row("types", value.id(), value.packageId(), value.namespace(), value.fabricApiVersion(), value.binaryName(), value.simpleName(), value.kind(), value.superclass(), value.sourcePath(), value.startOffset(), value.endOffset(), value.startLine(), value.endLine()));
        }
        for (var value : data.types()) {
            for (int ordinal = 0; ordinal < value.interfaces().size(); ordinal++) {
                rows.add(row("type_interfaces", value.id(), ordinal, value.interfaces().get(ordinal)));
            }
        }
        for (var value : data.fields()) {
            rows.add(row("fields", value.id(), value.typeId(), value.ordinal(), value.name(), value.type(), value.modifiers(), value.startOffset(), value.endOffset(), value.startLine(), value.endLine()));
        }
        for (var value : data.methods()) {
            rows.add(row("methods", value.id(), value.typeId(), value.ordinal(), value.name(), value.descriptor(), value.returnType(), value.modifiers(), value.constructor(), value.startOffset(), value.endOffset(), value.startLine(), value.endLine()));
        }
        for (var value : data.parameters()) {
            rows.add(row("parameters", value.id(), value.methodId(), value.ordinal(), value.name(), value.type(), value.varargs(), value.startOffset(), value.endOffset(), value.startLine(), value.endLine()));
        }
        return List.copyOf(rows);
    }

    private static String row(String table, Object... columns) {
        StringBuilder row = new StringBuilder(table);
        for (Object column : columns) {
            row.append('|').append(column);
        }
        return row.toString();
    }

    static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
