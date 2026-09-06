package dev.mcdevmcp.packaging;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java26PortGuardTest {
    private static final String PREVIEW_FLAG = "--enable" + "-preview";
    private static final Pattern PREVIOUS_RELEASE_PATTERN = Pattern.compile("Java\\s+(?:25|28)|JavaLanguageVersion\\.of\\((?:25|28)\\)|" + "options\\.release\\.set\\((?:25|28)\\)|java-version:\\s*['\"](?:25|28)['\"]|" + "(?:java|JAVA_HOME|artifacts|build|release)[_-]?(?:25|28)\\b", Pattern.CASE_INSENSITIVE);
    private static final List<Path> ACTIVE_JAVA_ROOTS = List.of(Path.of("src/main/java"), Path.of("src/test/java"), Path.of("src/runtimeTest/java"), Path.of("src/test/resources"), Path.of("mcp-tool-api/src/main/java"), Path.of("mcp-tool-api/src/test/java"), Path.of("mcp-tool-api/src/jpmsSmoke/java"), Path.of("benchmark/src/main/java"), Path.of("benchmark/src/test/java"), Path.of("conformance/src/main/java"));
    private static final List<Path> BUILD_FILES = List.of(Path.of("build.gradle.kts"), Path.of("mcp-tool-api/build.gradle.kts"), Path.of("benchmark/build.gradle.kts"), Path.of("conformance/build.gradle.kts"));
    private static final List<Path> ACTIVE_TEXT_FILES = List.of(Path.of(".github/workflows/ci.yml"), Path.of(".github/workflows/release.yml"), Path.of(".github/workflows/benchmark.yml"), Path.of("packaging/mcpb/bootstrap.cjs"), Path.of("scripts/build-mcpb.ps1"), Path.of("scripts/run-conformance.ps1"), Path.of("scripts/test-verify-release-assets.ps1"), Path.of("scripts/verify-release-assets.ps1"), Path.of("README.md"), Path.of("docs/ARCHITECTURE.md"), Path.of("skills/minecraft-dev-loop/SKILL.md"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void activeSourcesAndReleaseSurfacesRequireJava26WithoutPreview() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : ACTIVE_JAVA_ROOTS) {
            assertTrue(Files.isDirectory(root), () -> "Missing active Java source root: " + root);
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
                violations.addAll(parseErrors(sources));
                for (Path source : sources) {
                    inspectReleaseText(source, violations);
                }
            }
        }
        for (Path path : ACTIVE_TEXT_FILES) {
            inspectReleaseText(path, violations);
        }
        for (Path path : BUILD_FILES) {
            inspectReleaseText(path, violations);
            assertBuildTargets(path, Files.readString(path));
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    @Test
    void buildGuardRejectsSecondaryReleaseAndTestLauncherDrift() throws IOException {
        Path rootBuild = Path.of("build.gradle.kts");
        String source = Files.readString(rootBuild);
        int lastRelease = source.lastIndexOf("options.release.set(26)");
        String changedRelease = source.substring(0, lastRelease) + source.substring(lastRelease).replace("options.release.set(26)", "options.release.set(27)");
        assertThrows(AssertionError.class, () -> assertBuildTargets(rootBuild, changedRelease));
        assertThrows(AssertionError.class, () -> assertBuildTargets(rootBuild, source.replace("orElse(\"26\")", "orElse(\"27\")")));
        assertThrows(AssertionError.class, () -> assertBuildTargets(rootBuild, source.replace("require(feature == 26)", "require(feature == 27)")));
    }

    private static void assertBuildTargets(Path path, String source) {
        boolean conformance = path.equals(Path.of("conformance/build.gradle.kts"));
        List<String> versions = source.lines().map(String::trim).filter(line -> line.contains("languageVersion.set(")).toList();
        String stable = "languageVersion.set(JavaLanguageVersion.of(26))";
        String testLauncher = "languageVersion.set(testJavaFeature.map(JavaLanguageVersion::of))";
        assertEquals(conformance ? List.of(stable, stable) : List.of(testLauncher, stable), versions, path.toString());
        int releases = path.equals(Path.of("build.gradle.kts")) ? 3 : path.equals(Path.of("mcp-tool-api/build.gradle.kts")) ? 2 : 1;
        assertEquals(java.util.Collections.nCopies(releases, "options.release.set(26)"), source.lines().map(String::trim).filter(line -> line.contains("options.release.set(")).toList(), path.toString());
        if (!conformance) {
            assertEquals(List.of("val testJavaFeature = providers.gradleProperty(\"testJavaVersion\").orElse(\"26\").map { configuredVersion ->"), source.lines().map(String::trim).filter(line -> line.contains("val testJavaFeature =")).toList(), path.toString());
            assertEquals(List.of("require(feature == 26) { \"Java 26 is required for testJavaVersion, got $feature\" }"), source.lines().map(String::trim).filter(line -> line.contains("require(feature")).toList(), path.toString());
        }
    }

    @Test
    void compilerRejectsExperimentalDeclarationsIncludingNestedAndEscapedTokens() throws IOException {
        List<String> forbidden = List.of("final value " + "class Fixture {}", "class Fixture { value " + "record Nested() {} }", "final val" + "\\u0075e class Fixture {}");
        Path fixture = temporaryDirectory.resolve("Fixture.java");
        for (String source : forbidden) {
            Files.writeString(fixture, source);
            assertFalse(parseErrors(List.of(fixture)).isEmpty(), () -> "Compiler accepted: " + source);
        }
        Files.writeString(fixture, "class Fixture { String valueClass; String value = \"value " + "class\"; /* value " + "record */ }");
        assertTrue(parseErrors(List.of(fixture)).isEmpty());
    }

    @Test
    void olderSourceTargetsAreLimitedToIndexedSourceAndCompilerFixtures() throws IOException {
        Pattern sourceRelease = Pattern.compile("\"--release\"\\s*,\\s*\"(21|25)\"");
        Map<String, List<String>> actual = new TreeMap<>();
        for (Path root : ACTIVE_JAVA_ROOTS) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                    List<String> releases = sourceRelease.matcher(Files.readString(path)).results().map(match -> match.group(1)).toList();
                    if (!releases.isEmpty()) {
                        actual.put(path.toString().replace('\\', '/'), releases);
                    }
                }
            }
        }
        assertEquals(Map.of("src/main/java/dev/mcdevmcp/analysis/index/pipeline/CompilerConfiguration.java", List.of("25"), "src/test/java/dev/mcdevmcp/analysis/callgraph/CallgraphTestSupport.java", List.of("25", "25"), "src/test/java/dev/mcdevmcp/analysis/index/pipeline/IndexerTestSupport.java", List.of("25"), "src/test/java/dev/mcdevmcp/app/AnalysisPipelineIntegrationTest.java", List.of("21"), "src/test/java/dev/mcdevmcp/analysis/decompile/EmbeddedDecompilerTest.java", List.of("21"), "src/test/java/dev/mcdevmcp/analysis/decompile/EmbeddedRemapperTest.java", List.of("21"), "src/runtimeTest/java/dev/mcdevmcp/packaging/RuntimeArtifactSmokeMain.java", List.of("21", "21")), actual);
    }

    @Test
    void releaseGuardFindsLegacyRuntimeConfiguration() {
        assertTrue(PREVIOUS_RELEASE_PATTERN.matcher("Java " + "25 runtime").find());
        assertTrue(PREVIOUS_RELEASE_PATTERN.matcher("java-version: '" + "28'").find());
        assertFalse(PREVIOUS_RELEASE_PATTERN.matcher("ordinary valueClass and java" + "25Fixture identifiers").find());
    }

    private static void inspectReleaseText(Path path, List<String> violations) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        if (source.contains(PREVIEW_FLAG)) {
            violations.add(path + ": preview launcher/compiler flag");
        }
        if (PREVIOUS_RELEASE_PATTERN.matcher(source).find()) {
            violations.add(path + ": unsupported release dependency");
        }
    }

    private static List<String> parseErrors(List<Path> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A JDK compiler is required for the release source guard.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("--release", "26", "-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
            task.parse().forEach(ignored -> {
            });
        }
        return diagnostics.getDiagnostics().stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).map(Object::toString).toList();
    }
}
