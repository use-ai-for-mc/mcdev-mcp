package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.BiConsumer;

final class JavacPreflight {
    private JavacPreflight() {
    }

    static SourceCorpus preflight(IndexRequest request, CompilerClasspath classpath, SourceCorpus discovered, BiConsumer<JavacTask, Map<URI, CompilationUnitTree>> parsedUnitObserver) throws IndexBuildException, InterruptedException {
        request.cancellation().throwIfCancelled();
        JavaCompiler compiler = CompilerConfiguration.compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            StandardJavaFileManager standard = CompilerConfiguration.fileManager(compiler);
            try (MemorySourceFileManager manager = new MemorySourceFileManager(standard, discovered, classpath, request)) {
                List<JavaFileObject> explicit = discovered.sources().stream().map(manager::object).map(JavaFileObject.class::cast).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, JavacBatchParser.compilerFileManager(manager), diagnostics, CompilerConfiguration.options(), null, explicit);
                parsedUnitObserver.accept(task, new LinkedHashMap<>());
                List<? extends CompilationUnitTree> units = stream(task.parse());
                JavacDiagnostics.failOnSyntaxErrors(diagnostics.getDiagnostics(), discovered);
                Map<URI, DecodedSource> declarations = new HashMap<>();
                Map<String, DecodedSource> binaryNames = new HashMap<>();
                for (CompilationUnitTree unit : units) {
                    request.cancellation().throwIfCancelled();
                    DecodedSource source = discovered.require(unit.getSourceFile().toUri());
                    String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                    List<String> names = new ArrayList<>();
                    for (Tree declaration : unit.getTypeDecls()) {
                        if (declaration instanceof ClassTree type && !type.getSimpleName().isEmpty()) {
                            String simpleName = type.getSimpleName().toString();
                            String binaryName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                            DecodedSource previous = binaryNames.putIfAbsent(binaryName, source);
                            if (previous != null) {
                                throw new IndexBuildException("Duplicate source binary name " + binaryName + " in " + previous.absolutePath() + " and " + source.absolutePath());
                            }
                            names.add(simpleName);
                        }
                    }
                    declarations.put(source.uri(), source.withDeclarations(packageName, names));
                }
                if (declarations.size() != discovered.sources().size()) {
                    throw new IndexBuildException("Javac did not return every explicit in-memory source during preflight");
                }
                return new SourceCorpus(discovered.sources().stream().map(source -> declarations.get(source.uri())).toList());
            }
        } catch (IOException exception) {
            throw new IndexBuildException("Unable to configure Javac source preflight", exception);
        }
    }

    private static <T> List<T> stream(Iterable<? extends T> values) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }
}