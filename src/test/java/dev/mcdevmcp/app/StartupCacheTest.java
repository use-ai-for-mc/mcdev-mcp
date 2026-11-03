package dev.mcdevmcp.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StartupCacheTest {
    private static final List<String> QUIET_PREVIEW = List.of("--enable-preview", "-Xlog:disable", "-Xlog:all=warning,aot=off,cds=off:stderr");
    @TempDir
    Path temporaryDirectory;

    private Path jar() throws IOException {
        Path jar = temporaryDirectory.resolve("mcdev-mcp.jar");
        if (!Files.exists(jar)) {
            Files.writeString(jar, "jar bytes");
        }
        return jar;
    }

    private StartupCache.Launch launch(Path cache) throws IOException {
        List<String> arguments = new ArrayList<>(QUIET_PREVIEW);
        arguments.add(StartupCache.USE_OPTION + cache);
        return new StartupCache.Launch(arguments, jar().toString(), temporaryDirectory.resolve("runtime"));
    }

    private List<List<String>> started(StartupCache.Launch launch) throws IOException {
        List<List<String>> commands = new ArrayList<>();
        StartupCache.refresh(launch, commands::add);
        return commands;
    }

    @Test
    void aLaunchWithoutAConfiguredCacheStartsNoTrainer() throws Exception {
        var plain = new StartupCache.Launch(QUIET_PREVIEW, jar().toString(), temporaryDirectory.resolve("runtime"));

        assertEquals(Optional.empty(), StartupCache.configuredCache(plain.jvmArguments()));
        assertEquals(List.of(), started(plain));
    }

    @Test
    void aMissingCacheStartsATrainerThatRecordsUnderTheSameOptions() throws Exception {
        Path cache = temporaryDirectory.resolve("mcdev-mcp.aot");

        List<List<String>> commands = started(launch(cache));

        assertEquals(1, commands.size());
        List<String> command = commands.getFirst();
        assertTrue(Path.of(command.getFirst()).startsWith(temporaryDirectory.resolve("runtime").resolve("bin")), command.getFirst());
        assertEquals(QUIET_PREVIEW, command.subList(1, 4));
        assertEquals(StartupCache.RECORD_OPTION + temporaryDirectory.resolve("mcdev-mcp.aot.new"), command.get(4));
        assertEquals(List.of("-cp", jar().toString(), StartupCacheTrainer.class.getName(), cache.toString()), command.subList(5, command.size()));
        assertTrue(command.stream().noneMatch(argument -> argument.startsWith(StartupCache.USE_OPTION)));
    }

    @Test
    void aCacheRecordedForTheInstalledJarIsLeftAlone() throws Exception {
        Path cache = Files.writeString(temporaryDirectory.resolve("mcdev-mcp.aot"), "cache");
        Files.writeString(StartupCache.identityFile(cache), StartupCache.identity(jar().toString()), StandardCharsets.UTF_8);

        assertEquals(List.of(), started(launch(cache)));
    }

    @Test
    void aReplacedJarOrAMissingIdentityRecordsAgain() throws Exception {
        Path cache = Files.writeString(temporaryDirectory.resolve("mcdev-mcp.aot"), "cache");
        assertEquals(1, started(launch(cache)).size(), "a cache without an identity is not trusted");

        Files.writeString(StartupCache.identityFile(cache), StartupCache.identity(jar().toString()), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(jar(), FileTime.from(Instant.now().plusSeconds(TimeUnit.HOURS.toSeconds(1))));
        assertEquals(1, started(launch(cache)).size(), "a changed modification time invalidates the cache");

        Files.writeString(StartupCache.identityFile(cache), StartupCache.identity(jar().toString()), StandardCharsets.UTF_8);
        Files.writeString(jar(), "longer jar bytes");
        assertEquals(1, started(launch(cache)).size(), "a changed size invalidates the cache");
    }

    @Test
    void anInstallationThatCannotBeWrittenToStartsNoTrainer() throws Exception {
        Path cache = temporaryDirectory.resolve("missing-directory").resolve("mcdev-mcp.aot");

        assertEquals(List.of(), started(launch(cache)));
    }

    @Test
    void theTrainerRecordsACacheThatALaterLaunchAccepts() throws Exception {
        Path jar = Files.copy(Path.of(System.getProperty("mcdevMcpJar")), temporaryDirectory.resolve("installed.jar"));
        Path cache = temporaryDirectory.resolve("installed.aot");
        Path java = Path.of(System.getProperty("mcdevMcpJava"));
        List<String> arguments = new ArrayList<>(QUIET_PREVIEW);
        arguments.add(StartupCache.USE_OPTION + cache);
        var launch = new StartupCache.Launch(arguments, jar.toString(), java.getParent().getParent());

        var trainer = new ProcessBuilder(StartupCache.trainerCommand(launch, cache)).redirectErrorStream(true).redirectOutput(temporaryDirectory.resolve("trainer.log").toFile());
        trainer.environment().put("LOCALAPPDATA", temporaryDirectory.resolve("user").toString());
        trainer.environment().put("XDG_CACHE_HOME", temporaryDirectory.resolve("user").toString());
        trainer.environment().remove("JAVA_TOOL_OPTIONS");
        try (Process process = trainer.start()) {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the trainer did not finish");
            assertEquals(0, process.exitValue(), () -> log("trainer.log"));
        }

        assertTrue(Files.isRegularFile(cache), () -> log("trainer.log"));
        assertFalse(Files.exists(StartupCache.recording(cache)));
        assertTrue(StartupCache.current(cache, StartupCache.identity(jar.toString())));

        // AOTMode=required makes the JVM refuse to start unless it can use the cache.
        var accepted = new ProcessBuilder(java.toString(), "--enable-preview", "-XX:AOTMode=required",StartupCache.USE_OPTION + cache, "-cp", jar.toString(), Main.class.getName(), "--version").redirectErrorStream(true).redirectOutput(temporaryDirectory.resolve("accepted.log").toFile());
        accepted.environment().remove("JAVA_TOOL_OPTIONS");
        try (Process process = accepted.start()) {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the cached launch did not finish");
            assertEquals(0, process.exitValue(), () -> log("accepted.log"));
        }
        assertEquals(System.getProperty("mcdevMcpVersion"), log("accepted.log").strip());
    }

    private String log(String name) {
        try {
            return Files.readString(temporaryDirectory.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return exception.toString();
        }
    }
}
