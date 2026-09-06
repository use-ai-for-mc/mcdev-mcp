package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class ShadedJarSmokeTest {
    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));
    private static final Path JAVA = Path.of(System.getProperty("mcdevMcpJava"));

    private static Process start(Path localAppData, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(JAVA.toString());
        command.add("-Duser.home=" + localAppData);
        command.add("-jar");
        command.add(JAR.toString());
        command.addAll(java.util.List.of(arguments));
        var builder = new ProcessBuilder(command);
        builder.environment().put("LOCALAPPDATA", localAppData.toString());
        builder.environment().put("XDG_CACHE_HOME", localAppData.toString());
        return builder.start();
    }

    @Test
    void shadedJarHasRequiredManifestEntries() throws Exception {
        assertTrue(Files.isRegularFile(JAR));

        try (var jar = new JarFile(JAR.toFile())) {
            var manifest = jar.getManifest().getMainAttributes();
            assertEquals("dev.mcdevmcp.app.Main", manifest.getValue("Main-Class"));
            assertEquals(System.getProperty("mcdevMcpVersion"), manifest.getValue("Implementation-Version"));
            assertNull(manifest.getValue("Enable-Native-Access"));
            assertNotNull(jar.getEntry("META-INF/services/java.sql.Driver"));
            assertTrue(new String(jar.getInputStream(jar.getEntry("META-INF/services/java.sql.Driver")).readAllBytes()).contains("org.h2.Driver"));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().matches("META-INF/.*\\.(SF|RSA|DSA)")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/sqlite/") || entry.getName().startsWith("org/sqlite/native/")));
        }
    }

    @Test
    void shadedJarDiscoversH2ServiceUnderNativeAccessDenial() throws Exception {
        Path testClasses = Path.of(H2ServiceLoaderProbeMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path database = Files.createTempDirectory("h2-service-loader-smoke").resolve("smoke");
        String classpath = testClasses + java.io.File.pathSeparator + JAR;
        String stdout;
        String stderr;
        try (var process = new ProcessBuilder(JAVA.toString(), "--illegal-native-access=deny", "-cp", classpath, H2ServiceLoaderProbeMain.class.getName(), database.toString(), JAR.toString()).start();
             var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
             var errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = output.lines().collect(java.util.stream.Collectors.joining("\n"));
            stderr = errors.lines().collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(0, process.waitFor());
        }

        assertEquals("H2_SERVICE_OK", stdout);
        assertEquals("", stderr);
    }

    @Test
    void shadedJarPrintsItsManifestVersion() throws Exception {
        String output;
        try (var process = new ProcessBuilder(JAVA.toString(), "-jar", JAR.toString(), "--version").redirectErrorStream(true).start();
             var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
            assertEquals(0, process.waitFor());
        }

        assertEquals(System.getProperty("mcdevMcpVersion"), output);
    }

    @Test
    void shadedJarStartsWithNativeAccessDeniedWithoutWarnings() throws Exception {
        String stdout;
        String stderr;
        try (var process = new ProcessBuilder(JAVA.toString(), "--illegal-native-access=deny", "-jar", JAR.toString(), "--version").start();
             var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
             var errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = output.readLine();
            stderr = errors.lines().collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(0, process.waitFor());
        }

        assertEquals(System.getProperty("mcdevMcpVersion"), stdout);
        assertEquals("", stderr);
    }

    @Test
    void shadedJarCliHelpStatusAndServeEofStayOffline() throws Exception {
        Path localAppData = Files.createTempDirectory("shaded-cli-smoke");

        Process help = start(localAppData, "--help");
        String helpOutput = new String(help.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String helpError = new String(help.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(help.waitFor(Duration.ofSeconds(10)));
        assertEquals(0, help.exitValue());
        assertTrue(helpOutput.contains("Commands:"));
        assertEquals("", helpError);

        Process status = start(localAppData, "status");
        String statusOutput = new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String statusError = new String(status.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(status.waitFor(Duration.ofSeconds(10)));
        assertEquals(0, status.exitValue());
        assertEquals("Status: Not initialized" + System.lineSeparator() + "Run `mcdev-mcp init -v <version>` to set up." + System.lineSeparator(), statusOutput);
        assertEquals("", statusError);

        Process serve = start(localAppData, "serve");
        serve.getOutputStream().close();
        assertArrayEquals(new byte[0], serve.getInputStream().readAllBytes());
        assertArrayEquals(new byte[0], serve.getErrorStream().readAllBytes());
        assertTrue(serve.waitFor(Duration.ofSeconds(10)));
        assertEquals(0, serve.exitValue());
    }
}