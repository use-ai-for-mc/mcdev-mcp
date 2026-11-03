package dev.mcdevmcp.packaging;

import dev.mcdevmcp.support.RequiredRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredRuntimeContractTest {
    @Test
    void gradleFeatureMatchesTheRuntimeConstant() throws IOException {
        String feature = Files.readAllLines(Path.of("gradle.properties")).stream().filter(line -> line.startsWith("javaFeature=")).map(line -> line.substring("javaFeature=".length()).trim()).findFirst().orElseThrow();
        assertEquals(Integer.toString(RequiredRuntime.FEATURE), feature);
    }

    @Test
    void testRuntimeIsTheRequiredPreviewFeature() {
        assertEquals(RequiredRuntime.FEATURE, Runtime.version().feature());
        assertTrue(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview"));
    }
}
