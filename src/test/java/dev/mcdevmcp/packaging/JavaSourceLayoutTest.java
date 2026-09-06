package dev.mcdevmcp.packaging;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.mcdevmcp.support.JsonResourceReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceLayoutTest {
    @TempDir
    Path temporaryDirectory;

    private static void assertSourceLayout(Path sourceRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A system Java compiler is required to inspect Java source layout.");
        }

        Path root = sourceRoot.toAbsolutePath().normalize();
        List<Path> sources = javaSourcesUnder(root);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> compilationUnits = new ArrayList<>();
            task.parse().forEach(compilationUnits::add);
            for (CompilationUnitTree unit : compilationUnits) {
                Path source = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                String expectedPackage = expectedPackage(root, source);
                String declaredPackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                if (!expectedPackage.equals(declaredPackage)) {
                    throw new AssertionError(source + ": package path mismatch: expected '" + expectedPackage + "' but declared '" + declaredPackage + "'.\nJavac diagnostics:\n" + renderDiagnostics(diagnostics));
                }
                List<ClassTree> declarations = unit.getTypeDecls().stream().filter(type -> isNamedTopLevelDeclaration(type.getKind())).map(ClassTree.class::cast).toList();
                long expectedDeclarations = isPackageOrModuleInfo(source) ? 0 : 1;
                if (declarations.size() != expectedDeclarations) {
                    throw new AssertionError(source + ": expected " + expectedDeclarations + " named top-level declaration(s) but found " + declarations.size() + ".\nJavac diagnostics:\n" + renderDiagnostics(diagnostics));
                }
                if (expectedDeclarations == 1) {
                    String filename = source.getFileName().toString();
                    String expectedFilename = declarations.getFirst().getSimpleName() + ".java";
                    if (!filename.equals(expectedFilename)) {
                        throw new AssertionError(source + ": filename/simple-name mismatch: expected '" + expectedFilename + "' but found '" + filename + "'.\nJavac diagnostics:\n" + renderDiagnostics(diagnostics));
                    }
                }
            }
        }
    }

    private static void assertProductionSourceLayout(Path sourceRoot) throws IOException {
        assertSourceLayout(sourceRoot);
        assertNoTransitionalProductionSources(sourceRoot);
    }

    private static void assertNoTransitionalProductionSources(Path sourceRoot) throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<Path> sources = javaSourcesUnder(root);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A system Java compiler is required to inspect production source guards.");
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("--source", Integer.toString(Runtime.version().feature()), "-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> compilationUnits = new ArrayList<>();
            task.parse().forEach(compilationUnits::add);
            for (CompilationUnitTree unit : compilationUnits) {
                assertNoWireArgumentDeclarations(unit, sourcePath(unit));
            }
        }
        List<Path> closedSources = closedResultSources(root);
        if (!closedSources.isEmpty()) {
            assertNoClosedResultHazards(closedSources, true);
        }
    }

    private static Path sourcePath(CompilationUnitTree unit) {
        return Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
    }

    private static void assertNoWireArgumentDeclarations(CompilationUnitTree unit, Path source) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree declaration, Void ignored) {
                String simpleName = declaration.getSimpleName().toString();
                if (simpleName.endsWith("WireArguments")) {
                    throw new AssertionError(source + ": production *WireArguments declarations are forbidden: " + simpleName);
                }
                return super.visitClass(declaration, ignored);
            }
        }.scan(unit, null);
    }

    private static List<Path> closedResultSources(Path sourceRoot) {
        List<Path> candidates = List.of(sourceRoot.resolve("dev/mcdevmcp/bridge/BridgeResultDecoder.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/BridgeResultTypes.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/BridgeSession.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/BridgeStatusWire.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/LookedAtEntityWireResult.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/ScreenshotWireResult.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/TextureWireResult.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/RecordVideoWireResult.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/RecordVideoGridWireResult.java"), sourceRoot.resolve("dev/mcdevmcp/bridge/RecordVideoFramesWireResult.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/RuntimeToolSupport.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/MediaToolSupport.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/ScreenshotResult.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/TextureResult.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/RecordVideoResult.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/RecordVideoGridResult.java"), sourceRoot.resolve("dev/mcdevmcp/tools/runtime/RecordVideoFramesResult.java"));
        return candidates.stream().filter(Files::isRegularFile).toList();
    }

    private static void assertNoClosedResultHazards(List<Path> sources) throws IOException {
        assertNoClosedResultHazards(sources, false);
    }

    private static void assertNoClosedResultHazards(List<Path> sources, boolean requireApprovedDecode) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A system Java compiler is required to inspect closed-result source guards.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("--source", Integer.toString(Runtime.version().feature()), "-proc:none", "-classpath", System.getProperty("java.class.path", ""));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            try {
                task.analyze();
            } catch (RuntimeException exception) {
                throw new AssertionError("Javac attribution failed for closed-result sources.\n" + renderDiagnostics(diagnostics), exception);
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
                throw new AssertionError("Javac attribution produced errors for closed-result sources.\n" + renderDiagnostics(diagnostics));
            }
            ClosedResultScanner scanner = new ClosedResultScanner(Trees.instance(task), task.getTypes(), task.getElements());
            for (CompilationUnitTree unit : units) {
                scanner.scan(unit, sourcePath(unit));
            }
            if (requireApprovedDecode) {
                scanner.assertExactlyOneApprovedDecode();
            }
        }
    }

    private static final class ClosedResultScanner extends TreePathScanner<Void, Path> {
        private static final Set<String> FORBIDDEN_TYPE_NAMES = Set.of("dev.mcdevmcp.mcp.tool.api.TypedJson", "com.google.gson.Gson", "com.google.gson.GsonBuilder");
        private static final String DEBUGBRIDGE_PACKAGE = "com.debugbridge.";
        private static final String JSON_TYPE = "dev.mcdevmcp.mcp.tool.api.JsonType";
        private static final String APPROVED_DECODER = "dev.mcdevmcp.bridge.BridgeResultDecoder";
        private static final String MAP_TYPE = "java.util.Map";
        private static final String NUMBER_TYPE = "java.lang.Number";
        private static final Set<String> MAPPER_TYPES = Set.of("io.modelcontextprotocol.json.McpJsonMapper", "tools.jackson.databind.ObjectMapper", "com.fasterxml.jackson.databind.ObjectMapper");
        private final Trees trees;
        private final Types types;
        private final Elements elements;
        private int approvedDecodeCalls;

        private ClosedResultScanner(Trees trees, Types types, Elements elements) {
            this.trees = trees;
            this.types = types;
            this.elements = elements;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree unit, Path source) {
            String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            if (packageName.equals("com.debugbridge") || packageName.startsWith(DEBUGBRIDGE_PACKAGE)) {
                reject(source, unit, "forbidden DebugBridge implementation package: " + packageName);
            }
            return super.visitCompilationUnit(unit, source);
        }

        @Override
        public Void visitImport(ImportTree importTree, Path source) {
            String imported = importTree.getQualifiedIdentifier().toString();
            if (imported.equals("com.google.gson") || imported.startsWith("com.google.gson.")) {
                reject(source, importTree, "forbidden Gson type reference: " + imported);
            }
            if (imported.equals("com.debugbridge") || imported.startsWith(DEBUGBRIDGE_PACKAGE)) {
                reject(source, importTree, "forbidden DebugBridge implementation type reference: " + imported);
            }
            return super.visitImport(importTree, source);
        }

        @Override
        public Void visitNewClass(NewClassTree newClass, Path source) {
            rejectForbiddenType(new TreePath(getCurrentPath(), newClass.getIdentifier()), source, newClass);
            return super.visitNewClass(newClass, source);
        }

        @Override
        public Void visitIdentifier(IdentifierTree identifier, Path source) {
            rejectForbiddenType(getCurrentPath(), source, identifier);
            return super.visitIdentifier(identifier, source);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree select, Path source) {
            rejectForbiddenType(getCurrentPath(), source, select);
            Element member = trees.getElement(getCurrentPath());
            if (member instanceof VariableElement variable && variable.getSimpleName().contentEquals("CLASS") && isJsonTypeInfoId(variable.getEnclosingElement())) {
                reject(source, select, "forbidden Jackson class-name discriminator");
            }
            return super.visitMemberSelect(select, source);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree invocation, Path source) {
            String name = methodName(invocation.getMethodSelect());
            if (name.equals("get") || name.equals("intValue") || name.equals("longValue") || name.equals("convertValue") || name.equals("enableDefaultTyping") || name.equals("decode")) {
                rejectForbiddenInvocation(invocation, source, name);
            }
            return super.visitMethodInvocation(invocation, source);
        }

        private void rejectForbiddenInvocation(MethodInvocationTree invocation, Path source, String name) {
            TreePath methodPath = new TreePath(getCurrentPath(), invocation.getMethodSelect());
            Element element = trees.getElement(methodPath);
            if (!(element instanceof ExecutableElement)) {
                reject(source, invocation, "unable to attribute closed-result member invocation " + name);
            }
            ExecutableElement executable = (ExecutableElement) element;
            TypeMirror receiver = receiverType(invocation.getMethodSelect());
            TypeElement owner = executable.getEnclosingElement() instanceof TypeElement typeElement ? typeElement : null;
            if (name.equals("decode") && isTypeOrSubtype(receiver, owner, JSON_TYPE)) {
                if (!APPROVED_DECODER.equals(enclosingTypeName())) {
                    reject(source, invocation, "unauthorized JsonType.decode invocation");
                }
                if (++approvedDecodeCalls > 1) {
                    reject(source, invocation, "second JsonType.decode invocation");
                }
                return;
            }
            boolean forbidden = switch (name) {
                case "get" -> isTypeOrSubtype(receiver, owner, MAP_TYPE);
                case "intValue", "longValue" -> isTypeOrSubtype(receiver, owner, NUMBER_TYPE);
                case "convertValue" -> isMapperType(receiver, owner);
                case "enableDefaultTyping" -> true;
                default -> false;
            };
            if (forbidden) {
                reject(source, invocation, "forbidden closed-result member invocation " + name);
            }
        }

        private TypeMirror receiverType(ExpressionTree methodSelect) {
            if (!(methodSelect instanceof MemberSelectTree memberSelect)) {
                return null;
            }
            TypeMirror receiver = trees.getTypeMirror(new TreePath(getCurrentPath(), memberSelect.getExpression()));
            if (receiver == null || receiver.getKind() == TypeKind.ERROR || receiver.getKind() == TypeKind.NONE) {
                throw new AssertionError("Unable to attribute closed-result invocation receiver: " + memberSelect);
            }
            return receiver;
        }

        private boolean isTypeOrSubtype(TypeMirror receiver, TypeElement owner, String targetName) {
            TypeElement target = elements.getTypeElement(targetName);
            if (target == null) {
                throw new AssertionError("Unable to resolve closed-result guard target type: " + targetName);
            }
            if (receiver != null && receiver.getKind() != TypeKind.NONE && receiver.getKind() != TypeKind.ERROR) {
                return types.isSubtype(types.erasure(receiver), types.erasure(target.asType()));
            }
            return owner != null && types.isSubtype(types.erasure(owner.asType()), types.erasure(target.asType()));
        }

        private boolean isMapperType(TypeMirror receiver, TypeElement owner) {
            for (String targetName : MAPPER_TYPES) {
                if (elements.getTypeElement(targetName) != null && isTypeOrSubtype(receiver, owner, targetName)) {
                    return true;
                }
            }
            return false;
        }

        private String enclosingTypeName() {
            for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (path.getLeaf() instanceof ClassTree && trees.getElement(path) instanceof TypeElement typeElement) {
                    return typeElement.getQualifiedName().toString();
                }
            }
            throw new AssertionError("Unable to attribute closed-result enclosing type");
        }

        private void assertExactlyOneApprovedDecode() {
            if (approvedDecodeCalls != 1) {
                throw new AssertionError("Expected exactly one approved JsonType.decode invocation in " + APPROVED_DECODER + ", found " + approvedDecodeCalls);
            }
        }

        private void rejectForbiddenType(TreePath path, Path source, Tree tree) {
            Element element = trees.getElement(path);
            if (element instanceof TypeElement typeElement) {
                String name = typeElement.getQualifiedName().toString();
                if (FORBIDDEN_TYPE_NAMES.contains(name) || name.startsWith("com.google.gson.") || name.startsWith(DEBUGBRIDGE_PACKAGE)) {
                    reject(source, tree, "forbidden closed-result type reference: " + name);
                }
            }
        }

        private static boolean isJsonTypeInfoId(Element element) {
            return element instanceof TypeElement typeElement && typeElement.getQualifiedName().contentEquals("com.fasterxml.jackson.annotation.JsonTypeInfo.Id");
        }

        private static String methodName(ExpressionTree methodSelect) {
            return methodSelect instanceof MemberSelectTree memberSelect ? memberSelect.getIdentifier().toString() : ((IdentifierTree) methodSelect).getName().toString();
        }

        private static void reject(Path source, Tree tree, String message) {
            throw new AssertionError(source + ": " + message + " at " + tree);
        }
    }

    private static boolean isNamedTopLevelDeclaration(Tree.Kind kind) {
        return kind == Tree.Kind.CLASS || kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ENUM || kind == Tree.Kind.RECORD || kind == Tree.Kind.ANNOTATION_TYPE;
    }

    private static boolean isPackageOrModuleInfo(Path source) {
        String filename = source.getFileName().toString();
        return filename.equals("package-info.java") || filename.equals("module-info.java");
    }

    private static List<Path> javaSourcesUnder(Path sourceRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java")).sorted(Comparator.comparing(path -> sourceRoot.relativize(path).toString())).toList();
        }
    }

    private static String expectedPackage(Path sourceRoot, Path source) {
        Path relative = sourceRoot.relativize(source);
        Path parent = relative.getParent();
        if (parent == null) {
            return "";
        }
        StringBuilder packageName = new StringBuilder();
        for (Path segment : parent) {
            if (!packageName.isEmpty()) {
                packageName.append('.');
            }
            packageName.append(segment);
        }
        return packageName.toString();
    }

    private static String renderDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        return renderDiagnostics(diagnostics.getDiagnostics());
    }

    private static String renderDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        if (diagnostics.isEmpty()) {
            return "<none>";
        }
        return diagnostics.stream().map(diagnostic -> diagnostic.getKind() + " " + diagnosticSource(diagnostic) + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " " + diagnostic.getMessage(Locale.ROOT)).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    private static String diagnosticSource(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic.getSource() == null ? "<none>" : diagnostic.getSource().getName();
    }

    @Test
    void packagePathMismatchIsRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("sources");
        Path source = sourceRoot.resolve("expected/PackageMismatch.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package declared; class PackageMismatch {}\n");

        AssertionError failure = assertThrows(AssertionError.class, () -> assertSourceLayout(sourceRoot));

        assertEquals(source.toAbsolutePath().normalize() + ": package path mismatch: expected 'expected'" + " but declared 'declared'.\nJavac diagnostics:\n<none>", failure.getMessage());
    }

    @Test
    void twoNamedTopLevelDeclarationsAreRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("two-types");
        Path source = sourceRoot.resolve("TwoTypes.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class First {} interface Second {}\n");

        AssertionError failure = assertThrows(AssertionError.class, () -> assertSourceLayout(sourceRoot));

        assertEquals(source.toAbsolutePath().normalize() + ": expected 1 named top-level declaration(s) but found 2" + ".\nJavac diagnostics:\n<none>", failure.getMessage());
    }

    @Test
    void filenameSimpleNameMismatchIsRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("filename-mismatch");
        Path source = sourceRoot.resolve("WrongFile.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class ActualName {}\n");

        AssertionError failure = assertThrows(AssertionError.class, () -> assertSourceLayout(sourceRoot));

        assertEquals(source.toAbsolutePath().normalize() + ": filename/simple-name mismatch: expected 'ActualName.java'" + " but found 'WrongFile.java'.\nJavac diagnostics:\n<none>", failure.getMessage());
    }

    @Test
    void topLevelWireArgumentDeclarationsAreRejectedIndependentOfFilename() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("top-level-wire-arguments");
        Path source = sourceRoot.resolve("fixture/Holder.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; record TopLevelWireArguments(String value) {}\n");

        AssertionError failure = assertThrows(AssertionError.class, () -> assertNoTransitionalProductionSources(sourceRoot));

        assertTrue(failure.getMessage().contains("production *WireArguments declarations are forbidden: TopLevelWireArguments"));
    }

    @Test
    void nestedWireArgumentDeclarationsAreRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("nested-wire-arguments");
        Path source = sourceRoot.resolve("fixture/Holder.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; class Holder { record NestedWireArguments(String value) {} }\n");

        AssertionError failure = assertThrows(AssertionError.class, () -> assertNoTransitionalProductionSources(sourceRoot));

        assertTrue(failure.getMessage().contains("production *WireArguments declarations are forbidden: NestedWireArguments"));
    }

    @Test
    void localWireArgumentDeclarationsAreRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("local-wire-arguments");
        Path source = sourceRoot.resolve("fixture/Holder.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; class Holder { void create() { record LocalWireArguments(String value) {} } }\n");

        AssertionError failure = assertThrows(AssertionError.class, () -> assertNoTransitionalProductionSources(sourceRoot));

        assertTrue(failure.getMessage().contains("production *WireArguments declarations are forbidden: LocalWireArguments"));
    }

    @Test
    void intentionalProtocolAndPersistenceWireRecordsAreAllowed() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("intentional-wire-records");
        Path packageDirectory = sourceRoot.resolve("fixture");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("BridgeWireResponse.java"), "package fixture; record BridgeWireResponse(String value) {}\n");
        Files.writeString(packageDirectory.resolve("BridgeStatusWire.java"), "package fixture; record BridgeStatusWire(String value) {}\n");
        Files.writeString(packageDirectory.resolve("DownloadWire.java"), "package fixture; record DownloadWire(String value) {}\n");
        Files.writeString(packageDirectory.resolve("ScriptLogWireEntry.java"), "package fixture; record ScriptLogWireEntry(String value) {}\n");

        assertProductionSourceLayout(sourceRoot);
    }

    @Test
    void packageAndModuleInfoMayContainZeroNamedTopLevelDeclarations() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("zero-declarations");
        Path packageInfo = sourceRoot.resolve("fixture/package-info.java");
        Files.createDirectories(packageInfo.getParent());
        Files.writeString(packageInfo, "package fixture;\n");
        Files.writeString(sourceRoot.resolve("module-info.java"), "module fixture.module {}\n");

        assertSourceLayout(sourceRoot);
    }

    @Test
    void repositoryJavaSourcesFollowTheLayoutInvariant() throws IOException {
        assertProductionSourceLayout(Path.of("src/main/java"));
        Path testRoot = Path.of("src/test/java");
        var sourceRoots = List.of(testRoot, Path.of("mcp-tool-api/src/main/java"), Path.of("mcp-tool-api/src/test/java"), Path.of("mcp-tool-api/src/jpmsSmoke/java"), Path.of("benchmark/src/main/java"), Path.of("benchmark/src/test/java"), Path.of("conformance/src/main/java"));
        for (Path sourceRoot : sourceRoots) {
            assertSourceLayout(sourceRoot);
        }
    }

    @Test
    void retiredUnavailableToolArgumentsSourceRemainsDeleted() {
        assertFalse(Files.exists(Path.of("src/main/java/dev/mcdevmcp/mcp/tool/UnavailableToolArguments.java")));
    }

    @Test
    void closedDebugBridgeResultPathsRetainTheSingleTypedBoundary() throws IOException {
        List<Path> closedSources = closedResultSources(Path.of("src/main/java"));
        assertEquals(17, closedSources.size());
        assertNoClosedResultHazards(closedSources, true);
    }

    @Test
    void closedResultAstGuardRejectsEveryForbiddenConstructButAllowsSafeOpenJsonIteration() throws IOException {
        Path root = temporaryDirectory.resolve("closed-result-ast-guard");
        Path fixture = root.resolve("fixture/BadClosedResult.java");
        Files.createDirectories(fixture.getParent());
        List<ForbiddenCase> cases = List.of(new ForbiddenCase("member invocation get", "package fixture; import java.util.Map; class BadClosedResult { Object bad(Map<String, Object> object) { return object.get(\"value\"); } }\n", List.of(fixture)), new ForbiddenCase("member invocation intValue", "package fixture; class BadClosedResult { int bad(Number number) { return number.intValue(); } }\n", List.of(fixture)), new ForbiddenCase("member invocation longValue", "package fixture; class BadClosedResult { long bad(Number number) { return number.longValue(); } }\n", List.of(fixture)), new ForbiddenCase("member invocation convertValue", "package fixture; import io.modelcontextprotocol.json.McpJsonMapper; class BadClosedResult { Object bad(McpJsonMapper mapper, Object value) { return mapper.convertValue(value, Object.class); } }\n", List.of(fixture)), new ForbiddenCase("member invocation enableDefaultTyping", "package fixture; class BadClosedResult { Object bad(Unsafe mapper) { return mapper.enableDefaultTyping(); } static class Unsafe { Object enableDefaultTyping() { return null; } } }\n", List.of(fixture)), new ForbiddenCase("Jackson class-name discriminator", "package fixture; import com.fasterxml.jackson.annotation.JsonTypeInfo; class BadClosedResult { JsonTypeInfo.Id bad() { return JsonTypeInfo.Id.CLASS; } }\n", List.of(fixture)), new ForbiddenCase("Gson type reference", "package fixture; import com.google.gson.Gson; class BadClosedResult { Gson bad() { return new Gson(); } }\n", List.of(fixture, root.resolve("com/google/gson/Gson.java"))), new ForbiddenCase("TypedJson", "package fixture; import dev.mcdevmcp.mcp.tool.api.TypedJson; class BadClosedResult { TypedJson<String> bad() { return null; } }\n", List.of(fixture, root.resolve("dev/mcdevmcp/mcp/tool/api/TypedJson.java"))));
        Path gson = root.resolve("com/google/gson/Gson.java");
        Files.createDirectories(gson.getParent());
        Files.writeString(gson, "package com.google.gson; public class Gson {}\n");
        Path typedJson = root.resolve("dev/mcdevmcp/mcp/tool/api/TypedJson.java");
        Files.createDirectories(typedJson.getParent());
        Files.writeString(typedJson, "package dev.mcdevmcp.mcp.tool.api; public class TypedJson<T> {}\n");

        for (ForbiddenCase testCase : cases) {
            Files.writeString(fixture, testCase.source());
            AssertionError failure = assertThrows(AssertionError.class, () -> assertNoClosedResultHazards(testCase.sources()));
            assertTrue(failure.getMessage().contains(testCase.expectedMessage()), testCase.expectedMessage() + ": " + failure.getMessage());
        }

        Path decoder = root.resolve("dev/mcdevmcp/bridge/BridgeResultDecoder.java");
        Files.createDirectories(decoder.getParent());
        Files.writeString(decoder, "package dev.mcdevmcp.bridge; import dev.mcdevmcp.mcp.tool.api.JsonType; import io.modelcontextprotocol.json.McpJsonMapper; class BridgeResultDecoder { String bad(JsonType<String> type, McpJsonMapper mapper, Object value) { type.decode(mapper, value); return type.decode(mapper, value); } }\n");
        AssertionError secondDecode = assertThrows(AssertionError.class, () -> assertNoClosedResultHazards(List.of(decoder)));
        assertTrue(secondDecode.getMessage().contains("second JsonType.decode invocation"), secondDecode.getMessage());

        Path debugBridge = root.resolve("fixture/DebugBridgeReference.java");
        Path debugBridgeType = root.resolve("com/debugbridge/ImplementationType.java");
        Files.createDirectories(debugBridgeType.getParent());
        Files.writeString(debugBridgeType, "package com.debugbridge; public class ImplementationType {}\n");
        Files.writeString(debugBridge, "package fixture; import com.debugbridge.ImplementationType; class DebugBridgeReference { ImplementationType value; }\n");
        AssertionError debugBridgeFailure = assertThrows(AssertionError.class, () -> assertNoClosedResultHazards(List.of(debugBridge, debugBridgeType)));
        assertTrue(debugBridgeFailure.getMessage().contains("com.debugbridge"), debugBridgeFailure.getMessage());

        Path allowed = root.resolve("fixture/AllowedOpenJson.java");
        Files.writeString(allowed, "package fixture; import java.util.List; import java.util.function.Supplier; class AllowedOpenJson { Object read(List<Object> values, Supplier<Object> supplier) { return values.get(0) == null ? supplier.get() : values.get(0); } }\n");
        assertNoClosedResultHazards(List.of(allowed));
    }

    private record ForbiddenCase(String expectedMessage, String source, List<Path> sources) {
    }

    @Test
    void toolMetadataResourceContainsOnlyItsOrderedDescriptiveFields() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        List<Map<String, Object>> tools = mapper.readValue(new JsonResourceReader(mapper).readText("/mcp/tools.json"), new TypeRef<>() {
        });

        assertEquals(33, tools.size());
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> tool : tools) {
            assertEquals(List.of("name", "description"), List.copyOf(tool.keySet()));
            assertTrue(tool.get("name") instanceof String name && !name.isBlank());
            assertInstanceOf(String.class, tool.get("description"));
            assertTrue(names.add((String) tool.get("name")));
        }
        assertEquals(33, names.size());
    }
}
