package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.h2.SymbolRepository;
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
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class IndexerTestSupport {
    private IndexerTestSupport() {
    }

    static IndexRequest request(Path sourceRoot, Path remappedJar, Path outputDatabase, int threads) {
        return request(List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), sourceRoot)), remappedJar, List.of(), outputDatabase, threads);
    }

    static IndexRequest request(List<SourceRoot> sourceRoots, Path remappedJar, List<Path> classpath, Path outputDatabase, int threads) {
        return new IndexRequest(new MinecraftVersion("1.21.5"), sourceRoots, remappedJar, classpath, outputDatabase, threads, (_, _, _) -> {
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
        return new SymbolRepository(database).query(IndexerTestSupport::dump);
    }

    private static List<String> dump(Connection connection) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.addAll(query(connection, "metadata", "SELECT singleton, schema_version, minecraft_version, source_root, remapped_jar_sha256 FROM metadata ORDER BY singleton"));
        rows.addAll(query(connection, "packages", "SELECT id, source_namespace, fabric_api_version, name FROM packages ORDER BY id"));
        rows.addAll(query(connection, "types", "SELECT id, package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, superclass_binary_name, source_path, start_offset, end_offset, start_line, end_line FROM types ORDER BY id"));
        rows.addAll(query(connection, "type_interfaces", "SELECT type_id, ordinal, interface_binary_name FROM type_interfaces ORDER BY type_id, ordinal"));
        rows.addAll(query(connection, "fields", "SELECT id, type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line FROM fields ORDER BY id"));
        rows.addAll(query(connection, "methods", "SELECT id, type_id, ordinal, name, descriptor, return_type, modifiers, constructor, start_offset, end_offset, start_line, end_line FROM methods ORDER BY id"));
        rows.addAll(query(connection, "parameters", "SELECT id, method_id, ordinal, name, type, varargs, start_offset, end_offset, start_line, end_line FROM parameters ORDER BY id"));
        return List.copyOf(rows);
    }

    private static List<String> query(Connection connection, String table, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.createStatement(); var results = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = results.getMetaData();
            while (results.next()) {
                StringBuilder row = new StringBuilder(table);
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    row.append('|').append(results.getObject(column));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
