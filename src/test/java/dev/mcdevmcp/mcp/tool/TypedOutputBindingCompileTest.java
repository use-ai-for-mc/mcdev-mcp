package dev.mcdevmcp.mcp.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedOutputBindingCompileTest {
    private static final String POSITIVE_FIXTURE = "compile-positive/CorrectTypedOutputBindings.java";
    private static final List<String> NEGATIVE_FIXTURES = List.of("compile-negative/IncorrectStructuredOutputBinding.java", "compile-negative/IncorrectParameterizedOutputBinding.java", "compile-negative/StructuredResultFromContentBinding.java");

    @TempDir
    Path temporaryDirectory;

    @Test
    void positiveFixtureCompilesWithEverySupportedOutputBindingShape() throws IOException {
        Path source = copyFixture(POSITIVE_FIXTURE);
        CompilationResult result = compile(source);

        assertTrue(result.success(), () -> "positive output-binding fixture did not compile:\n" + result.renderDiagnostics());
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.WARNING), () -> "positive output-binding fixture emitted warnings:\n" + result.renderDiagnostics());
        assertFixtureHasNoUnsafeShortcuts(source);
    }

    @Test
    void incompatibleOutputBindingsFailAtTheirHandlerExpressions() throws IOException {
        for (String fixture : NEGATIVE_FIXTURES) {
            Path source = copyFixture(fixture);
            String original = Files.readString(source, StandardCharsets.UTF_8);
            Path corrected = temporaryDirectory.resolve("corrected").resolve(source.getFileName());
            Files.createDirectories(corrected.getParent());
            Files.copy(source, corrected);
            Files.writeString(corrected, correctedSource(fixture, original), StandardCharsets.UTF_8);
            CompilationResult correctedResult = compile(corrected);

            assertTrue(correctedResult.success(), () -> fixture + " corrected control did not compile:\n" + correctedResult.renderDiagnostics());
            assertFixtureHasNoUnsafeShortcuts(corrected);
            CompilationResult result = compile(source);

            assertFalse(result.success(), () -> fixture + " unexpectedly compiled");
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR), () -> fixture + " did not report a compiler error:\n" + result.renderDiagnostics());
            List<String> lines = sourceLines(source);
            int expressionLine = java.util.stream.IntStream.range(0, lines.size()).filter(index -> lines.get(index).contains("ToolResult.structured")).map(index -> index + 1).findFirst().orElseThrow(() -> new AssertionError("fixture has no intentionally incompatible handler expression: " + fixture));
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getLineNumber() >= expressionLine - 1 && diagnostic.getLineNumber() <= expressionLine + 1), () -> fixture + " compiler error did not point at its incompatible handler expression:\n" + result.renderDiagnostics());
            assertFixtureHasNoUnsafeShortcuts(source);
        }
    }

    private CompilationResult compile(Path source) throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler is required for fixture compilation");
        Path classes = Files.createDirectories(temporaryDirectory.resolve(source.getFileName().toString() + ".classes"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("--release", Integer.toString(Runtime.version().feature()), "-Xlint:all", "-Werror", "-proc:none", "-classpath", Objects.requireNonNull(System.getProperty("java.class.path"), "test classpath"), "-d", classes.toString());
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(source.toFile());
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics, options, null, units).call());
            return new CompilationResult(success, List.copyOf(diagnostics.getDiagnostics()));
        }
    }

    private Path copyFixture(String resource) throws IOException {
        Path source = temporaryDirectory.resolve(resource);
        Files.createDirectories(source.getParent());
        try (InputStream stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(resource), resource)) {
            Files.copy(stream, source);
        }
        return source;
    }

    private static String correctedSource(String fixture, String source) {
        if (fixture.endsWith("IncorrectStructuredOutputBinding.java")) {
            return source.replace("new Wrong()", "new Expected()");
        }
        if (fixture.endsWith("IncorrectParameterizedOutputBinding.java")) {
            return source.replace("List<Integer>", "List<String>").replace("List.of(1)", "List.of(\"ok\")");
        }
        if (fixture.endsWith("StructuredResultFromContentBinding.java")) {
            return source.replace("ToolResult.structured(new Summary(), \"structured\")", "ToolResult.text(\"content\")");
        }
        throw new AssertionError("No corrected compiler control for " + fixture);
    }

    private static List<String> sourceLines(Path source) throws IOException {
        return Files.readAllLines(source, StandardCharsets.UTF_8);
    }

    private static void assertFixtureHasNoUnsafeShortcuts(Path source) throws IOException {
        List<String> lines = sourceLines(source);
        assertTrue(lines.stream().noneMatch(line -> line.contains("@SuppressWarnings")), () -> source + " uses warning suppression");
        assertTrue(lines.stream().noneMatch(line -> line.matches(".*\\bTool(OutputBinding|Binding|Result)\\s+[^<].*")), () -> source + " uses a raw typed binding/result");
        assertTrue(lines.stream().noneMatch(line -> line.matches(".*\\(\\s*(ToolResult|ToolOutputBinding|ToolBinding)(\\s*<|\\s*\\)).*")), () -> source + " uses a cast to bypass the binding relationship");
    }

    private record CompilationResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        private String renderDiagnostics() {
            if (diagnostics.isEmpty()) {
                return "<none>";
            }
            return diagnostics.stream().sorted(Comparator.comparingLong(Diagnostic::getLineNumber)).map(diagnostic -> diagnostic.getKind() + " " + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " " + diagnostic.getMessage(Locale.ROOT)).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        }
    }
}
