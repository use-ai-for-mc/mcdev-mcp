package dev.mcdevmcp.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestRuntimeSelectionTest {
    @Test
    void testJvmUsesTheRequestedFeatureVersion() {
        var requestedFeature = System.getProperty("dev.mcdevmcp.test.javaFeature");

        assertNotNull(requestedFeature, "Gradle must report the requested test JVM feature version");
        assertEquals(Integer.parseInt(requestedFeature), Runtime.version().feature());
    }

    @Test
    void spawnedJarProbeUsesTheTestJvmExecutable() throws Exception {
        var configuredExecutable = System.getProperty("mcdevMcpJava");
        assertNotNull(configuredExecutable, "Gradle must configure the spawned-JAR Java executable");

        var executableName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var testJvmExecutable = Path.of(System.getProperty("java.home"), "bin", executableName).toRealPath();
        var probeExecutable = Path.of(configuredExecutable).toRealPath();
        assertEquals(testJvmExecutable, probeExecutable);

        try (var process = new ProcessBuilder(probeExecutable.toString(), "-XshowSettings:properties", "-version").redirectErrorStream(true).start()) {
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            assertTrue(output.contains("java.specification.version = " + Runtime.version().feature()), () -> "Spawned Java version did not match the test JVM:\n" + output);
        }

        System.out.printf("TEST_RUNTIME feature=%d testJvm=%s spawnedJarJava=%s%n", Runtime.version().feature(), testJvmExecutable, probeExecutable);
    }
}
