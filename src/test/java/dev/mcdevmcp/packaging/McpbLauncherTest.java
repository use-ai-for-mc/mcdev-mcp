package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpbLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsJava25BeforeStartingTheServer() throws Exception {
        ProcessResult result = runLauncher("25", "17", Map.of());

        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("Java 26 or newer is required"));
        assertFalse(Files.exists(temporaryDirectory.resolve("environment.txt")));
    }

    @Test
    void acceptsJava26And27AndForwardsTheChildExitStatus() throws Exception {
        ProcessResult java26 = runLauncher("26", "17", Map.of());

        assertEquals(17, java26.exitCode(), java26.error());
        String launchedArguments = Files.readString(temporaryDirectory.resolve("arguments.txt"), StandardCharsets.UTF_8);
        assertTrue(launchedArguments.startsWith("-jar|"));
        assertTrue(launchedArguments.endsWith("|serve"));

        ProcessResult java27 = runLauncher("27", "17", Map.of());
        assertEquals(17, java27.exitCode(), java27.error());
    }

    @Test
    void ignoresNumericJavaToolOptionsPreambleWhenDetectingTheFeatureVersion() throws Exception {
        ProcessResult result = runLauncher("26", "17", Map.of("FAKE_JAVA_VERSION_PREAMBLE", "Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8"));

        assertEquals(17, result.exitCode(), result.error());
    }

    @Test
    void rejectsAFailedVersionProbeEvenWhenItPrintsASupportedVersion() throws Exception {
        ProcessResult result = runLauncher("26", "17", Map.of("FAKE_JAVA_VERSION_EXIT", "7"));

        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("Unable to determine Java version"));
        assertFalse(Files.exists(temporaryDirectory.resolve("environment.txt")));
    }

    @Test
    void stripsOnlyUnresolvedOptionalConfigurationValues() throws Exception {
        ProcessResult result = runLauncher("28", "0", Map.of("MCDEV_SESSION_LOG_DIR", "${user_config.script_logs}", "MCDEV_RUN_COMMAND", "false", "MCDEV_MCP_DEBUG_LOG", "${user_config.debug_log}", "MCDEV_INDEX_THREADS", "${user_config.index_threads}", "DEBUGBRIDGE_PORT", "${user_config.debugbridge_port}"));

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("||false||", Files.readString(temporaryDirectory.resolve("environment.txt"), StandardCharsets.UTF_8).trim());
    }

    @Test
    void forwardsInterruptAndTerminationToTheLaunchedJavaProcess() throws Exception {
        assertSignalForwarded("SIGINT");
        assertSignalForwarded("SIGTERM");
    }

    private void assertSignalForwarded(String signal) throws Exception {
        Path bundle = temporaryDirectory.resolve(signal.toLowerCase());
        Files.createDirectories(bundle);
        Path fixture = temporaryDirectory.resolve(signal + "-java.cjs");
        Files.writeString(bundle.resolve("mcdev-mcp.jar"), "");
        Path childPid = temporaryDirectory.resolve(signal + "-child.pid");
        Path childSignal = temporaryDirectory.resolve(signal + "-child.signal");
        Path runner = temporaryDirectory.resolve(signal + "-runner.cjs");
        Files.writeString(fixture, """
                                   "use strict";
                                   const fs = require("node:fs");
                                   if (process.argv[2] === "-version") {
                                      process.stderr.write('java version "26"\\n');
                                     process.exit(0);
                                   }
                                   fs.writeFileSync(process.env.FAKE_CHILD_PID, String(process.pid));
                                   for (const signal of ["SIGINT", "SIGTERM"]) {
                                     process.on(signal, () => {
                                       fs.writeFileSync(process.env.FAKE_CHILD_SIGNAL, signal);
                                       process.exit(0);
                                     });
                                   }
                                    setInterval(() => {}, 1000);
                                   """, StandardCharsets.UTF_8);
        String source = Files.readString(Path.of("packaging/mcpb/bootstrap.cjs"), StandardCharsets.UTF_8);
        String escapedFixture = fixture.toString().replace("\\", "\\\\");
        source = source.replace("const javaCommand = \"java\";", "const javaCommand = process.execPath;").replace("return commandArguments;", "return [\"" + escapedFixture + "\", ...commandArguments];");
        Files.writeString(bundle.resolve("bootstrap.cjs"), source, StandardCharsets.UTF_8);
        Files.writeString(runner, """
                                  "use strict";
                                  const {spawn} = require("node:child_process");
                                  const fs = require("node:fs");
                                  const [bundle, signal, pidFile] = process.argv.slice(2);
                                  const launcher = spawn(process.execPath, ["bootstrap.cjs"], {cwd: bundle, env: process.env});
                                  const deadline = Date.now() + 5000;
                                  const timer = setInterval(() => {
                                    if (fs.existsSync(pidFile)) {
                                      clearInterval(timer);
                                      launcher.kill(signal);
                                    } else if (Date.now() > deadline) {
                                      clearInterval(timer);
                                      launcher.kill();
                                      process.exitCode = 1;
                                    }
                                  }, 20);
                                  launcher.once("exit", (code) => { process.exitCode = code ?? 0; });
                                  """, StandardCharsets.UTF_8);

        var builder = new ProcessBuilder("node", runner.toString(), bundle.toString(), signal, childPid.toString());
        Map<String, String> environment = builder.environment();
        environment.put("FAKE_CHILD_PID", childPid.toString());
        environment.put("FAKE_CHILD_SIGNAL", childSignal.toString());
        try (Process launcher = builder.start()) {
            try {
                assertTrue(launcher.waitFor(Duration.ofSeconds(8).toMillis(), TimeUnit.MILLISECONDS), "launcher did not stop after " + signal);
                assertTrue(Files.exists(childPid), "fake Java child did not start");
                long pid = Long.parseLong(Files.readString(childPid));
                assertTrue(ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive(), "fake Java child survived " + signal);
                if (!System.getProperty("os.name").startsWith("Windows")) {
                    assertEquals(signal, Files.readString(childSignal));
                }
            } finally {
                launcher.descendants().forEach(ProcessHandle::destroyForcibly);
                launcher.destroyForcibly();
                launcher.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
                if (Files.exists(childPid)) {
                    ProcessHandle.of(Long.parseLong(Files.readString(childPid))).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        }
    }

    private static void writeInstrumentedLauncher(Path bundle, Path command) throws Exception {
        String source = Files.readString(Path.of("packaging/mcpb/bootstrap.cjs"), StandardCharsets.UTF_8);
        String escapedCommand = command.toString().replace("\\", "\\\\");
        source = source.replace("const javaCommand = \"java\";", "const javaCommand = process.execPath;").replace("return commandArguments;", "return [\"" + escapedCommand + "\", ...commandArguments];");
        Files.writeString(bundle.resolve("bootstrap.cjs"), source, StandardCharsets.UTF_8);
    }

    private ProcessResult runLauncher(String feature, String childExit, Map<String, String> additions) throws Exception {
        Path fakeJava = temporaryDirectory.resolve("fake-java.cjs");
        Path bundle = temporaryDirectory.resolve("bundle");
        Files.createDirectories(bundle);
        writeInstrumentedLauncher(bundle, fakeJava);
        Files.writeString(bundle.resolve("mcdev-mcp.jar"), "");
        Files.writeString(fakeJava, """
                                    "use strict";
                                    const fs = require("node:fs");
                                    if (process.argv[2] === "-version") {
                                      const preamble = process.env.FAKE_JAVA_VERSION_PREAMBLE;
                                      if (preamble) {
                                        process.stderr.write(`${preamble}\\n`);
                                      }
                                      process.stderr.write(`java version "${process.env.FAKE_JAVA_VERSION}"\\n`);
                                      process.exit(Number.parseInt(process.env.FAKE_JAVA_VERSION_EXIT ?? "0", 10));
                                    }
                                    fs.writeFileSync(process.env.FAKE_ARGS_FILE, process.argv.slice(2).join("|"));
                                    const names = [
                                      "MCDEV_SESSION_LOG_DIR",
                                      "MCDEV_MCP_DEBUG_LOG",
                                      "MCDEV_RUN_COMMAND",
                                      "MCDEV_INDEX_THREADS",
                                      "DEBUGBRIDGE_PORT"
                                    ];
                                    fs.writeFileSync(process.env.FAKE_ENV_FILE, names.map((name) => process.env[name] ?? "").join("|"));
                                    process.exit(Number.parseInt(process.env.FAKE_JAVA_EXIT, 10));
                                    """, StandardCharsets.UTF_8);

        var builder = new ProcessBuilder("node", "bootstrap.cjs").directory(bundle.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("FAKE_JAVA_VERSION", feature);
        environment.put("FAKE_JAVA_EXIT", childExit);
        environment.put("FAKE_ENV_FILE", temporaryDirectory.resolve("environment.txt").toString());
        environment.put("FAKE_ARGS_FILE", temporaryDirectory.resolve("arguments.txt").toString());
        environment.putAll(additions);
        try (Process launched = builder.start()) {
            String stdout = new String(launched.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(launched.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessResult(launched.waitFor(), stdout, stderr);
        }
    }

    private record ProcessResult(int exitCode, String output, String error) {
    }
}
