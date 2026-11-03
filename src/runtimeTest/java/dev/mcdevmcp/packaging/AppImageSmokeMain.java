package dev.mcdevmcp.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.mcdevmcp.packaging.PackagedLauncher.APPLICATION;
import static dev.mcdevmcp.packaging.PackagedLauncher.require;

/**
 * Exercises a packaged application image: its launcher must report the release version and answer
 * an MCP session before and after it has recorded its startup cache.
 */
final class AppImageSmokeMain {
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(60);

    private AppImageSmokeMain() {
    }

    static void main(String[] arguments) throws Exception {
        String version = arguments[1];
        Path work = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path image = PackagedLauncher.install(Path.of(arguments[0]).toAbsolutePath().normalize(), work);
        Path launcher = launcher(image);
        require(version.equals(reportedVersion(work, launcher)), "The launcher does not report version " + version);

        var sessions = PackagedLauncher.coldThenCached(work, image, List.of(launcher.toString(), "serve"), Map.of());
        System.out.printf("Application image answered initialize after %d ms without a startup cache and %d ms with one.%n", sessions.cold().answeredMillis(), sessions.cached().answeredMillis());
    }

    private static Path launcher(Path image) {
        return Stream.of(image.resolve(APPLICATION).resolve(APPLICATION + ".exe"), image.resolve(APPLICATION).resolve("bin").resolve(APPLICATION), image.resolve(APPLICATION + ".app").resolve("Contents").resolve("MacOS").resolve(APPLICATION)).filter(Files::isRegularFile).findFirst().orElseThrow(() -> new IllegalStateException("No launcher found in " + image));
    }

    private static String reportedVersion(Path work, Path launcher) throws Exception {
        Path captured = work.resolve("version.txt");
        try (Process process = PackagedLauncher.command(work, List.of(launcher.toString(), "--version")).redirectErrorStream(true).redirectOutput(captured.toFile()).start()) {
            require(process.waitFor(VERSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "The launcher did not report its version in time");
            require(process.exitValue() == 0, "The launcher failed to report its version: " + Files.readString(captured));
        }
        return Files.readString(captured, StandardCharsets.UTF_8).strip();
    }
}
