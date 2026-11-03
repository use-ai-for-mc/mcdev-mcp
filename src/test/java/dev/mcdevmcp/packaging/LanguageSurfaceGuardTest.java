package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LanguageSurfaceGuardTest {
    private static final List<Path> JAVA_ROOTS = List.of(Path.of("src/main/java"), Path.of("src/test/java"), Path.of("src/runtimeTest/java"), Path.of("mcp-tool-api/src/main/java"), Path.of("mcp-tool-api/src/test/java"), Path.of("mcp-tool-api/src/jpmsSmoke/java"), Path.of("storage-model/src/main/java"), Path.of("storage-model/src/test/java"));

    @Test
    void olderSourceTargetsAreLimitedToIndexedSourceAndCompilerFixtures() throws IOException {
        Pattern sourceRelease = Pattern.compile("\"--release\"\\s*,\\s*\"(21|25)\"");
        Map<String, List<String>> actual = new TreeMap<>();
        for (Path root : JAVA_ROOTS) {
            assertTrue(Files.isDirectory(root), () -> "Missing Java source root: " + root);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                    List<String> releases = sourceRelease.matcher(Files.readString(path)).results().map(match -> match.group(1)).toList();
                    if (!releases.isEmpty()) {
                        actual.put(path.toString().replace('\\', '/'), releases);
                    }
                }
            }
        }
        assertEquals(Map.of("src/main/java/dev/mcdevmcp/analysis/index/pipeline/CompilerConfiguration.java", List.of("25"), "src/main/java/dev/mcdevmcp/storage/migration/SourceCacheValidator.java", List.of("25"), "src/test/java/dev/mcdevmcp/storage/migration/SourceCacheValidatorTest.java", List.of("21"), "src/test/java/dev/mcdevmcp/storage/migration/SourceProducerIdentityTest.java", List.of("21"), "src/test/java/dev/mcdevmcp/analysis/callgraph/CallgraphTestSupport.java", List.of("25", "25"), "src/test/java/dev/mcdevmcp/analysis/index/pipeline/IndexerTestSupport.java", List.of("25"), "src/test/java/dev/mcdevmcp/app/AnalysisPipelineIntegrationTest.java", List.of("21"), "src/test/java/dev/mcdevmcp/analysis/decompile/EmbeddedDecompilerTest.java", List.of("21"), "src/test/java/dev/mcdevmcp/analysis/decompile/EmbeddedRemapperTest.java", List.of("21"), "src/runtimeTest/java/dev/mcdevmcp/packaging/RuntimeArtifactSmokeMain.java", List.of("21", "21")), actual);
    }
}