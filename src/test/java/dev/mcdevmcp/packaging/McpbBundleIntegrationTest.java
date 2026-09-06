package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class McpbBundleIntegrationTest {
    @Test
    void packagingAssetsContainOnlyTheMinimalLauncherAndPinnedBuildDependency() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path launcher = root.resolve("packaging/mcpb/bootstrap.cjs");
        Path packageJson = root.resolve("packaging/mcpb/package.json");
        Path lock = root.resolve("packaging/mcpb/package-lock.json");

        String launcherText = Files.readString(launcher, StandardCharsets.UTF_8);
        assertTrue(launcherText.contains("node:child_process"));
        assertTrue(launcherText.contains("mcdev-mcp.jar"));
        assertFalse(launcherText.contains("tools/list"));
        assertFalse(launcherText.contains("JSON-RPC"));
        assertTrue(Files.readString(packageJson).contains("\"@anthropic-ai/mcpb\": \"2.1.2\""));
        assertTrue(Files.readString(lock).contains("\"@anthropic-ai/mcpb\": \"2.1.2\""));
    }

    @Test
    void shadedJarIsTheOnlyServerRuntimeArtifact() throws Exception {
        Path jar = Path.of(System.getProperty("mcdevMcpJar"));
        assertTrue(Files.isRegularFile(jar));
        try (var archive = new JarFile(jar.toFile())) {
            assertEquals("dev.mcdevmcp.app.Main", archive.getManifest().getMainAttributes().getValue("Main-Class"));
        }
    }
}
