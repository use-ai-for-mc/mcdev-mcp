package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NodeCorpusCaptureInteropTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String TREE = "b".repeat(40);
    private static final String SOURCE_HASH = "1".repeat(64);
    private static final String JAR_HASH = "2".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"1.21.11", "26.1"})
    void workflowCaptureProducesTheTypedBaselineContract(String version) throws Exception {
        String workflow = Files.readString(Path.of("../.github/workflows/benchmark.yml"));
        String marker = "<<'NODE_CAPTURE'\n";
        String normalized = workflow.replace("\r\n", "\n");
        int start = normalized.indexOf(marker);
        assertTrue(start >= 0, "Workflow must contain the Node capture producer");
        start += marker.length();
        int end = normalized.indexOf("          NODE_CAPTURE\n", start);
        assertTrue(end > start, "Workflow capture heredoc must terminate");
        Path capture = Files.writeString(temporaryDirectory.resolve("capture.mjs"), normalized.substring(start, end).stripIndent());

        // Execute the workflow's real exporter with only the expensive oracle inputs replaced.
        Files.writeString(temporaryDirectory.resolve("package.json"), "{\"type\":\"module\"}");
        Path indexer = Files.createDirectories(temporaryDirectory.resolve("dist/indexer"));
        Files.writeString(indexer.resolve("index.js"), """
                                                       export async function buildIndex(options) {
                                                         if (options.minecraftVersion !== process.env.EXPECTED_VERSION) throw new Error('Indexer version mismatch');
                                                         return { minecraftPackages: ['sample'], totalClasses: 1 };
                                                       }
                                                       """);
        Path callgraph = Files.createDirectories(temporaryDirectory.resolve("dist/callgraph"));
        Files.writeString(callgraph.resolve("query.js"), """
                                                         import { join } from 'node:path';
                                                         export function getCallgraphDbPath(version) {
                                                           if (version !== process.env.EXPECTED_VERSION) throw new Error('Callgraph version mismatch');
                                                           return join(process.env.XDG_CACHE_HOME, version + '.db');
                                                         }
                                                         export async function openDb(version) {
                                                           getCallgraphDbPath(version);
                                                           return { exec: () => [{ values: [[3]] }] };
                                                         }
                                                         export function closeDb() {}
                                                         """);
        Path cache = Files.createDirectories(temporaryDirectory.resolve("cache"));
        Path indexRoot = Files.createDirectories(cache.resolve("mcdev-mcp/index").resolve(version).resolve("minecraft"));
        Files.writeString(indexRoot.resolve("sample.json"), """
                                                            {"classes":{"Target":{"fields":[{"name":"value"}],"methods":[{"params":[{"name":"input"}]}]}}}
                                                            """);
        Path database = Files.writeString(cache.resolve(version + ".db"), "fixture callgraph bytes");
        String databaseHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database)));
        Path reviewed = Files.writeString(temporaryDirectory.resolve("reviewed.json"), """
                                                                                       {"nodeCallgraphIdentity":{"generator":"fixture-generator","generatorArtifactSha256":"%s","protocol":"method-call-tab-v1","databaseSchema":"sqlite-calls-v1"},"probes":[]}
                                                                                       """.formatted("3".repeat(64)));
        Path output = temporaryDirectory.resolve("captured.json");
        Path log = temporaryDirectory.resolve("capture.log");
        ProcessBuilder builder = new ProcessBuilder("node", capture.toString(), temporaryDirectory.toString(), version, reviewed.toString(), output.toString(), COMMIT, TREE, SOURCE_HASH, JAR_HASH, databaseHash, database.toString()).directory(temporaryDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("MCDEV_NODE_ORACLE_COMMIT", COMMIT);
        builder.environment().put("MCDEV_NODE_ORACLE_TREE", TREE);
        builder.environment().put("XDG_CACHE_HOME", cache.toString());
        builder.environment().put("EXPECTED_VERSION", version);
        // Process.close() waits indefinitely; keep test shutdown bounded.
        @SuppressWarnings("resource") Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Node capture timed out");
            assertEquals(0, process.exitValue(), () -> readLog(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Node capture did not stop after timeout");
            }
        }

        String json = Files.readString(output);
        NodeCorpusBaseline baseline = McpJsonDefaults.getMapper().readValue(json, NodeCorpusBaseline.class);
        assertEquals(1, baseline.schemaVersion());
        assertEquals(new MinecraftVersion(version), baseline.minecraftVersion());
        assertEquals(SOURCE_HASH, baseline.sourceLogicalHash());
        assertEquals(JAR_HASH, baseline.remappedJarSha256());
        assertEquals(databaseHash, baseline.nodeCallgraphSha256());
        assertEquals(new NodeOracleIdentity(COMMIT, TREE), baseline.oracleIdentity());
        assertEquals(new CorpusIndexCounts(1, 1, 1, 1, 1), baseline.indexCounts());
        assertEquals(new CorpusCallgraphCounts(3, 3, 3), baseline.callgraphCounts());

        String scalarVersion = "\"minecraftVersion\":\"" + version + "\"";
        assertTrue(json.contains(scalarVersion));
        String objectVersion = json.replace(scalarVersion, "\"minecraftVersion\":{\"value\":\"" + version + "\"}");
        assertThrows(IOException.class, () -> McpJsonDefaults.getMapper().readValue(objectVersion, NodeCorpusBaseline.class));
        assertTrue(normalized.contains("(.minecraftVersion == $version)"), "Reviewed-baseline comparison must check the scalar version");
        assertFalse(normalized.contains(".minecraftVersion.value"), "Workflow must not read an object-form version");
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException exception) {
            return exception.toString();
        }
    }
}
