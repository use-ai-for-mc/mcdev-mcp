package dev.mcdevmcp.mcp.tool;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Keeps the heterogeneous binding boundary honest without matching comments,
 * strings, or unrelated types that happen to share a simple name.
 */
class TypedOutputBindingSourceGuardTest {
    private static final List<String> GUARDED_TYPES = List.of("dev.mcdevmcp.mcp.tool.api.ToolResult", "dev.mcdevmcp.mcp.tool.api.ToolOutputBinding", "dev.mcdevmcp.mcp.tool.api.ToolBinding");

    @TempDir
    Path temporaryDirectory;

    @Test
    void productionBindingCatalogAndAdapterUseOnlyResolvedGenericTypes() throws IOException {
        assertSafe(List.of(Path.of("mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/ToolBinding.java"), Path.of("mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/ToolOutputBinding.java"), Path.of("src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java"), Path.of("src/main/java/dev/mcdevmcp/mcp/transport/McpSdkAdapter.java")));
    }

    @Test
    void resolvedGuardRejectsRawTypesCastsAndUnsafeSuppressions() throws IOException {
        assertRejected("package fixture; import dev.mcdevmcp.mcp.tool.api.ToolResult; class Raw { ToolResult value; }", "raw");
        assertRejected("package fixture; import dev.mcdevmcp.mcp.tool.api.ToolOutputBinding; class Raw { ToolOutputBinding value; }", "raw");
        assertRejected("package fixture; import java.util.List; import dev.mcdevmcp.mcp.tool.api.ToolResult; class NestedRaw { List<ToolResult> value; }", "raw");
        assertRejected("package fixture; import dev.mcdevmcp.mcp.tool.api.ToolResult; class Cast { Object value(Object input) { return (ToolResult<?>) input; } }", "cast");
        assertRejected("package fixture; import dev.mcdevmcp.mcp.tool.api.ToolResult; class Suppressed { @SuppressWarnings(\"unchecked\") ToolResult<?> value; }", "suppression");
        assertRejected("package fixture; import java.util.List; import dev.mcdevmcp.mcp.tool.api.ToolResult; class Combined { static final String RAW_WARNING = \"rawtypes\"; @SuppressWarnings(RAW_WARNING) List<ToolResult> value; }", "suppression");
    }

    @Test
    void resolvedGuardAllowsParameterizedWildcardAndUnrelatedSimpleNames() throws IOException {
        Path source = writeFixture("fixture/Allowed.java", "package fixture; import java.util.List; import dev.mcdevmcp.mcp.tool.api.ToolBinding; import dev.mcdevmcp.mcp.tool.api.ToolResult; class Allowed { String text = \"ToolResult result\"; // ToolOutputBinding raw\n ToolBinding<?> binding; ToolResult<?> result; List<ToolResult<?>> nested; ToolResult<?>[] array; }\n");
        Path unrelated = writeFixture("fixture/ToolResult.java", "package fixture; class ToolResult {}\n");

        assertSafe(List.of(source, unrelated));
    }

    @Test
    void resultMetadataIsAbsentFromAllProductionAndTestSources() throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<Path> sources = new java.util.ArrayList<>();
        for (String sourceRoot : List.of("mcp-tool-api/src/main/java", "mcp-tool-api/src/test/java", "mcp-tool-api/src/jpmsSmoke/java", "src/main/java", "src/test/java")) {
            Path directory = root.resolve(sourceRoot);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.walk(directory)) {
                stream.filter(path -> path.toString().endsWith(".java")).filter(path -> !path.getFileName().toString().equals("module-info.java")).forEach(sources::add);
            }
        }
        assertNoResultMetadata(sources);
    }

    @Test
    void resultMetadataGuardRejectsReintroducedDeclarations() throws IOException {
        assertResultMetadataRejected("package dev.mcdevmcp.mcp.tool.api; interface ToolResult<O> { static <O> O structured(Class<O> type, O value, String text) { return value; } }", "structured factory");
        assertResultMetadataRejected("package dev.mcdevmcp.mcp.tool.api; interface ToolResult<O> { static <O> StructuredToolResult<O> structured(JsonType<O> type, String text) { return null; } }", "structured factory");
        assertResultMetadataRejected("package dev.mcdevmcp.mcp.tool.api; record StructuredToolResult<T>(java.util.List<io.modelcontextprotocol.spec.McpSchema.Content> content, Object structured" + "Type, T value, boolean error) {}", "constructor metadata");
        assertResultMetadataRejected("package dev.mcdevmcp.mcp.tool.api; record StructuredToolResult<T>(java.util.List<io.modelcontextprotocol.spec.McpSchema.Content> content, JsonType<T> type, T value) {}", "constructor metadata");
        assertResultMetadataRejected("package dev.mcdevmcp.mcp.tool.api; record StructuredToolResult<T>(java.util.List<io.modelcontextprotocol.spec.McpSchema.Content> content, T value, boolean error) { Object structured" + "Type() { return null; } }", "type metadata");
    }

    private void assertRejected(String sourceText, String label) throws IOException {
        Path source = writeFixture("fixture/Guarded.java", sourceText);
        AssertionError failure = assertThrows(AssertionError.class, () -> assertSafe(List.of(source)), label);
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(label), failure.getMessage());
    }

    private Path writeFixture(String relativePath, String sourceText) throws IOException {
        Path source = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, sourceText, StandardCharsets.UTF_8);
        return source;
    }

    private void assertResultMetadataRejected(String sourceText, String label) throws IOException {
        Path source = writeFixture("metadata/" + label.replace(' ', '-') + ".java", sourceText);
        AssertionError failure = assertThrows(AssertionError.class, () -> assertNoResultMetadata(List.of(source)), label);
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(label), failure.getMessage());
    }

    private static void assertSafe(List<Path> sources) throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler is required for source guards");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("--source", Integer.toString(Runtime.version().feature()), "-proc:none", "-classpath", Objects.requireNonNull(System.getProperty("java.class.path"), "test classpath"));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> units = new java.util.ArrayList<>();
            task.parse().forEach(units::add);
            try {
                task.analyze();
            } catch (RuntimeException exception) {
                throw new AssertionError("Javac attribution failed for typed output source guard:\n" + renderDiagnostics(diagnostics), exception);
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
                throw new AssertionError("Javac attribution produced errors for typed output source guard:\n" + renderDiagnostics(diagnostics));
            }
            SourceScanner scanner = new SourceScanner(Trees.instance(task));
            for (CompilationUnitTree unit : units) {
                scanner.scan(unit, sourceOf(unit));
            }
        }
    }

    private static void assertNoResultMetadata(List<Path> sources) throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler is required for source guards");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("--source", Integer.toString(Runtime.version().feature()), "-proc:none", "-classpath", Objects.requireNonNull(System.getProperty("java.class.path"), "test classpath"));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> units = new java.util.ArrayList<>();
            task.parse().forEach(units::add);
            try {
                task.analyze();
            } catch (RuntimeException exception) {
                throw new AssertionError("Javac attribution failed for result metadata guard:\n" + renderDiagnostics(diagnostics), exception);
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
                throw new AssertionError("Javac attribution produced errors for result metadata guard:\n" + renderDiagnostics(diagnostics));
            }
            ResultMetadataScanner scanner = new ResultMetadataScanner(Trees.instance(task), task.getTypes());
            for (CompilationUnitTree unit : units) {
                scanner.scan(unit, sourceOf(unit));
            }
        }
    }

    private static Path sourceOf(CompilationUnitTree unit) {
        return Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
    }

    private static String renderDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        if (diagnostics.getDiagnostics().isEmpty()) {
            return "<none>";
        }
        return diagnostics.getDiagnostics().stream().map(diagnostic -> diagnostic.getKind() + " " + diagnostic.getMessage(Locale.ROOT)).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    private static final class SourceScanner extends TreePathScanner<Void, Path> {
        private final Trees trees;

        private SourceScanner(Trees trees) {
            this.trees = trees;
        }

        @Override
        public Void visitIdentifier(IdentifierTree identifier, Path source) {
            if (isTypePosition(getCurrentPath()) && isRawGuardedType(getCurrentPath())) {
                reject(source, identifier, "raw");
            }
            return super.visitIdentifier(identifier, source);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree select, Path source) {
            if (isTypePosition(getCurrentPath()) && isRawGuardedType(getCurrentPath())) {
                reject(source, select, "raw");
            }
            return super.visitMemberSelect(select, source);
        }

        @Override
        public Void visitTypeCast(TypeCastTree cast, Path source) {
            if (isGuardedType(new TreePath(getCurrentPath(), cast.getType()))) {
                reject(source, cast, "cast");
            }
            return super.visitTypeCast(cast, source);
        }

        @Override
        public Void visitAnnotation(AnnotationTree annotation, Path source) {
            if (hasUnsafeSuppression(getCurrentPath().getParentPath())) {
                reject(source, annotation, "suppression");
            }
            return super.visitAnnotation(annotation, source);
        }

        private boolean isTypePosition(TreePath path) {
            TreePath parentPath = path.getParentPath();
            if (parentPath == null) {
                return false;
            }
            return switch (parentPath.getLeaf().getKind()) {
                case ANNOTATED_TYPE, ANNOTATION, ARRAY_TYPE, CATCH, CLASS, INSTANCE_OF, INTERSECTION_TYPE, METHOD,
                     NEW_CLASS, PARAMETERIZED_TYPE, TYPE_CAST, TYPE_PARAMETER, UNION_TYPE, VARIABLE, UNBOUNDED_WILDCARD,
                     EXTENDS_WILDCARD, SUPER_WILDCARD -> true;
                default -> false;
            };
        }

        private boolean hasUnsafeSuppression(TreePath declarationPath) {
            for (TreePath path = declarationPath; path != null; path = path.getParentPath()) {
                Element annotated = trees.getElement(path);
                if (annotated == null) {
                    continue;
                }
                for (AnnotationMirror mirror : annotated.getAnnotationMirrors()) {
                    Element annotationType = mirror.getAnnotationType().asElement();
                    if (!(annotationType instanceof TypeElement type) || !type.getQualifiedName().contentEquals("java.lang.SuppressWarnings")) {
                        continue;
                    }
                    for (var entry : mirror.getElementValues().entrySet()) {
                        if (entry.getKey() instanceof ExecutableElement element && element.getSimpleName().contentEquals("value") && hasUnsafeWarningValue(entry.getValue())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean hasUnsafeWarningValue(AnnotationValue value) {
            Object resolved = value.getValue();
            if (resolved instanceof String string) {
                return string.equals("unchecked") || string.equals("rawtypes");
            }
            if (resolved instanceof List<?> values) {
                return values.stream().filter(AnnotationValue.class::isInstance).map(AnnotationValue.class::cast).anyMatch(this::hasUnsafeWarningValue);
            }
            return false;
        }

        private boolean isGuardedType(TreePath path) {
            Element element = trees.getElement(path);
            return element instanceof TypeElement type && GUARDED_TYPES.contains(type.getQualifiedName().toString());
        }

        private boolean isRawGuardedType(TreePath path) {
            TreePath typePath = parameterizedRoot(path);
            Element element = trees.getElement(typePath);
            if (!(element instanceof TypeElement type) || !GUARDED_TYPES.contains(type.getQualifiedName().toString())) {
                return false;
            }
            if (!(trees.getTypeMirror(typePath) instanceof DeclaredType declared)) {
                return false;
            }
            return declared.getTypeArguments().isEmpty();
        }

        private TreePath parameterizedRoot(TreePath path) {
            TreePath parent = path.getParentPath();
            if (parent != null && parent.getLeaf() instanceof ParameterizedTypeTree parameterized && parameterized.getType() == path.getLeaf()) {
                return parent;
            }
            return path;
        }

        private static void reject(Path source, Tree tree, String kind) {
            throw new AssertionError(source + ": " + kind + " bypasses the typed output binding boundary at " + tree);
        }
    }

    private static final class ResultMetadataScanner extends TreePathScanner<Void, Path> {
        private static final String TOOL_RESULT = "dev.mcdevmcp.mcp.tool.api.ToolResult";
        private static final String STRUCTURED_RESULT = "dev.mcdevmcp.mcp.tool.api.StructuredToolResult";
        private static final String CONTENT = "io.modelcontextprotocol.spec.McpSchema.Content";
        private final Trees trees;
        private final Types types;

        private ResultMetadataScanner(Trees trees, Types types) {
            this.trees = trees;
            this.types = types;
        }

        @Override
        public Void visitClass(ClassTree type, Path source) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof TypeElement owner && owner.getQualifiedName().contentEquals(STRUCTURED_RESULT)) {
                for (Element member : owner.getEnclosedElements()) {
                    if (member instanceof ExecutableElement constructor && constructor.getKind() == ElementKind.CONSTRUCTOR && hasInvalidStructuredConstructorSignature(owner, constructor)) {
                        reject(source, type, "result-level constructor metadata");
                    }
                }
            }
            return super.visitClass(type, source);
        }

        @Override
        public Void visitMethod(MethodTree tree, Path source) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner) {
                if (owner.getQualifiedName().contentEquals(TOOL_RESULT) && method.getSimpleName().contentEquals("structured") && hasInvalidStructuredFactorySignature(method)) {
                    reject(source, tree, "result-level structured factory metadata");
                }
                if (owner.getQualifiedName().contentEquals(STRUCTURED_RESULT) && method.getSimpleName().contentEquals("structured" + "Type")) {
                    reject(source, tree, "result-level type metadata");
                }
            }
            return super.visitMethod(tree, source);
        }

        @Override
        public Void visitVariable(VariableTree variable, Path source) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null && element.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals(STRUCTURED_RESULT) && element.getSimpleName().contentEquals("structured" + "Type")) {
                reject(source, variable, "result-level type metadata");
            }
            return super.visitVariable(variable, source);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree invocation, Path source) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement method && method.getSimpleName().contentEquals("structured") && method.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals(TOOL_RESULT) && hasInvalidStructuredFactorySignature(method)) {
                reject(source, invocation, "result-level structured factory metadata");
            }
            return super.visitMethodInvocation(invocation, source);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree select, Path source) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement method && method.getSimpleName().contentEquals("structured" + "Type") && method.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals(STRUCTURED_RESULT)) {
                reject(source, select, "result-level type metadata");
            }
            return super.visitMemberSelect(select, source);
        }

        @Override
        public Void visitNewClass(NewClassTree expression, Path source) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement constructor && constructor.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals(STRUCTURED_RESULT) && hasInvalidStructuredConstructorSignature(owner, constructor)) {
                reject(source, expression, "result-level constructor metadata");
            }
            return super.visitNewClass(expression, source);
        }

        private boolean hasInvalidStructuredFactorySignature(ExecutableElement method) {
            if (!method.getModifiers().contains(Modifier.STATIC) || method.getTypeParameters().size() != 1 || method.getParameters().size() != 2) {
                return true;
            }
            TypeMirror outputType = method.getTypeParameters().getFirst().asType();
            if (!types.isSameType(method.getParameters().getFirst().asType(), outputType) || isNotDeclared(method.getParameters().get(1).asType(), "java.lang.String")) {
                return true;
            }
            if (!(method.getReturnType() instanceof DeclaredType result) || isNotDeclared(result, STRUCTURED_RESULT) || result.getTypeArguments().size() != 1) {
                return true;
            }
            return !types.isSameType(result.getTypeArguments().getFirst(), outputType);
        }

        private boolean hasInvalidStructuredConstructorSignature(TypeElement owner, ExecutableElement constructor) {
            if (constructor.getParameters().size() != 3 || owner.getTypeParameters().size() != 1) {
                return true;
            }
            TypeMirror content = constructor.getParameters().getFirst().asType();
            if (!(content instanceof DeclaredType list) || isNotDeclared(list, "java.util.List") || list.getTypeArguments().size() != 1 || isNotDeclared(list.getTypeArguments().getFirst(), CONTENT)) {
                return true;
            }
            return !types.isSameType(constructor.getParameters().get(1).asType(), owner.getTypeParameters().getFirst().asType()) || constructor.getParameters().get(2).asType().getKind() != TypeKind.BOOLEAN;
        }

        private static boolean isNotDeclared(TypeMirror mirror, String qualifiedName) {
            return !(mirror instanceof DeclaredType declared && declared.asElement() instanceof TypeElement type && type.getQualifiedName().contentEquals(qualifiedName));
        }

        private static void reject(Path source, Tree tree, String kind) {
            throw new AssertionError(source + ": " + kind + " is forbidden at " + tree);
        }
    }
}
