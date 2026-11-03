package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class McpbBundleIntegrationTest {
    @Test
    void packagingAssetsAreTheManifestTemplateAndThePinnedPackerOnly() throws Exception {
        Path packaging = Path.of("packaging/mcpb");

        try (Stream<Path> files = Files.list(packaging)) {
            assertEquals(Set.of("manifest.template.json", "package.json", "package-lock.json"), files.map(file -> file.getFileName().toString()).filter(name -> !name.equals("node_modules")).collect(Collectors.toSet()));
        }
        assertTrue(Files.readString(packaging.resolve("package.json")).contains("\"@anthropic-ai/mcpb\": \"2.1.2\""));
        assertTrue(Files.readString(packaging.resolve("package-lock.json")).contains("\"@anthropic-ai/mcpb\": \"2.1.2\""));
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
