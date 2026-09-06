package dev.mcdevmcp.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppVersionTest {
    @Test
    void readsTheGradleFilteredVersionFromClasses() {
        assertEquals(System.getProperty("mcdevMcpVersion"), AppVersion.current());
    }

    @Test
    void derivesTheExecutableJarNameFromTheApplicationVersion() {
        assertEquals("mcdev-mcp-" + System.getProperty("mcdevMcpVersion") + ".jar", AppVersion.executableJarName());
    }

    @Test
    void rejectsTestResourceFallbackWhenTheTestGuardIsDisabled() {
        String property = AppVersion.TEST_FALLBACK_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            assertThrows(IllegalStateException.class, AppVersion::current);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            }
            else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void debugLogEnvironmentRulesMatchTheNodeServer() {
        assertFalse(new AppEnvironment(Map.of()).debugLogPath().isPresent());
        assertFalse(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "")).debugLogPath().isPresent());
        assertFalse(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "off")).debugLogPath().isPresent());
        assertEquals(Path.of("/tmp/mcdev-debug.log"), new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "on")).debugLogPath().orElseThrow());
        assertEquals(Path.of("OFF"), new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "OFF")).debugLogPath().orElseThrow());
        assertEquals(Path.of("ON"), new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "ON")).debugLogPath().orElseThrow());
        assertEquals(" on ", new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", " on ")).value("MCDEV_MCP_DEBUG_LOG").orElseThrow());
        assertEquals(Path.of("logs/mcdev.log"), new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "logs/mcdev.log")).debugLogPath().orElseThrow());
    }

    @Test
    void truthyValuesAndIndexThreadLimitsAreDeterministic() {
        var environment = new AppEnvironment(Map.of("FLAG", "true", "MCDEV_INDEX_THREADS", "99"));

        assertTrue(environment.isTruthy("FLAG"));
        assertFalse(new AppEnvironment(Map.of("FLAG", "yes")).isTruthy("FLAG"));
        assertFalse(new AppEnvironment(Map.of("FLAG", "on")).isTruthy("FLAG"));
        assertEquals(8, environment.indexThreads(8));
    }

    @Test
    void debugLogWritesOnlyToItsConfiguredFileAndSwallowsFailures() throws Exception {
        Path directory = Files.createTempDirectory("mcdev-debug-log");
        Path logPath = directory.resolve("debug.log");

        DebugLog.write(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", logPath.toString())), "diagnostic");
        DebugLog.write(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", directory.toString())), "ignored");

        assertEquals("diagnostic" + System.lineSeparator(), Files.readString(logPath));
    }

    @Test
    void debugLogSwallowsInvalidPathFailures() {
        DebugLog.write(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "\u0000invalid")), "ignored");
    }

    @Test
    void versionFallbackIsNotPackagedWithExplodedMainResources() {
        assertFalse(Files.exists(Path.of("build", "resources", "main", "version.properties")));
    }
}
