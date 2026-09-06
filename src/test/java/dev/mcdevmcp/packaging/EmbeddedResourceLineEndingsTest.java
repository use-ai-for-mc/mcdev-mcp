package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedResourceLineEndingsTest {
    private static final List<String> RESOURCES = List.of("guides/dev-loop.txt", "guides/python-scripting.txt", "mcp/tools.json");
    private static final List<String> LF_RULES = List.of("/src/main/resources/**/*.txt text eol=lf", "/src/main/resources/**/*.json text eol=lf");
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporaryDirectory;

    @Test
    void actualShippedResourcesAreLfAndCopiedWithoutTransformation() throws Exception {
        for (String resource : RESOURCES) {
            byte[] shipped = resourceBytes(resource);
            assertFalse(containsCarriageReturn(shipped), resource);
            assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources").resolve(resource)), shipped, resource);
        }
    }

    @Test
    void gitCheckoutIsStableForBothAutocrlfModesAndNegativeControlReproducesCrLf() throws Exception {
        String attributes = Files.readString(Path.of(".gitattributes"));
        Path positive = createRepository("positive", attributes);
        for (boolean autoCrlf : List.of(true, false)) {
            checkout(positive, autoCrlf);
            for (String resource : RESOURCES) {
                assertArrayEquals(resourceBytes(resource), Files.readAllBytes(positive.resolve("src/main/resources").resolve(resource)), resource + " core.autocrlf=" + autoCrlf);
            }
        }

        String withoutRules = String.join("\n", attributes.lines().filter(line -> !LF_RULES.contains(line)).toList()) + "\n";
        Path negative = createRepository("negative", withoutRules);
        checkout(negative, true);
        for (String resource : RESOURCES) {
            byte[] checkedOut = Files.readAllBytes(negative.resolve("src/main/resources").resolve(resource));
            assertTrue(containsCarriageReturn(checkedOut), "Negative control must reproduce CRLF: " + resource);
            String normalized = new String(checkedOut, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertArrayEquals(resourceBytes(resource), normalized.getBytes(StandardCharsets.UTF_8), resource);
        }
        checkout(negative, false);
        for (String resource : RESOURCES) {
            assertArrayEquals(resourceBytes(resource), Files.readAllBytes(negative.resolve("src/main/resources").resolve(resource)), resource);
        }
    }

    private Path createRepository(String name, String attributes) throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve(name));
        git(repository, false, "init", "--quiet");
        Files.writeString(repository.resolve(".gitattributes"), attributes);
        for (String resource : RESOURCES) {
            Path path = repository.resolve("src/main/resources").resolve(resource);
            Files.createDirectories(path.getParent());
            Files.write(path, resourceBytes(resource));
        }
        git(repository, false, "add", "--", ".gitattributes", "src/main/resources");
        return repository;
    }

    private void checkout(Path repository, boolean autoCrlf) throws Exception {
        for (String resource : RESOURCES) {
            Files.delete(repository.resolve("src/main/resources").resolve(resource));
        }
        git(repository, autoCrlf, "checkout-index", "--all", "--force");
    }

    private void git(Path repository, boolean autoCrlf, String... arguments) throws Exception {
        Path emptyConfiguration = temporaryDirectory.resolve("empty-git-config");
        if (Files.notExists(emptyConfiguration)) {
            Files.createFile(emptyConfiguration);
        }
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=" + autoCrlf, "-c", "core.eol=lf", "-c", "core.attributesFile=" + emptyConfiguration));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporaryDirectory, "git-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", emptyConfiguration.toString());
        Process process = builder.start();
        Throwable failure = null;
        try {
            process.getOutputStream().close();
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Git test process exceeded " + GIT_TIMEOUT);
            }
            assertEquals(0, process.exitValue(), Files.readString(log));
        } catch (Exception | Error original) {
            failure = original;
        } finally {
            boolean interrupted = Thread.interrupted() || failure instanceof InterruptedException;
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IOException("Git test process did not terminate");
                    }
                }
            } catch (Exception | Error cleanupFailure) {
                interrupted |= cleanupFailure instanceof InterruptedException;
                if (failure == null) {
                    failure = cleanupFailure;
                }
                else {
                    failure.addSuppressed(cleanupFailure);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static byte[] resourceBytes(String resource) throws IOException {
        try (var input = Objects.requireNonNull(EmbeddedResourceLineEndingsTest.class.getResourceAsStream("/" + resource), resource)) {
            return input.readAllBytes();
        }
    }

    private static boolean containsCarriageReturn(byte[] bytes) {
        for (byte value : bytes) {
            if (value == '\r') {
                return true;
            }
        }
        return false;
    }
}
