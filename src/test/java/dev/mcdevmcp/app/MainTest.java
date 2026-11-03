package dev.mcdevmcp.app;

import dev.mcdevmcp.support.RequiredRuntime;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void versionExitsSuccessfullyAndPrintsTheReleaseVersion() {
        var output = new StringWriter();

        int exitCode = Main.execute(new String[]{"--version"}, RequiredRuntime.FEATURE, new PrintWriter(output), new PrintWriter(new StringWriter()));

        assertEquals(0, exitCode);
        assertEquals(System.getProperty("mcdevMcpVersion") + System.lineSeparator(), output.toString());
    }

    @Test
    void rejectsAnyOtherFeatureBeforeCommandExecution() {
        for (int feature : List.of(RequiredRuntime.FEATURE - 1, RequiredRuntime.FEATURE + 1)) {
            var output = new StringWriter();
            var error = new StringWriter();

            int exitCode = Main.execute(new String[]{"--version"}, feature, new PrintWriter(output), new PrintWriter(error));

            assertEquals(1, exitCode);
            assertEquals("", output.toString());
            assertTrue(error.toString().contains(RequiredRuntime.previewRequirement() + "; detected Java " + feature));
        }
    }
}
