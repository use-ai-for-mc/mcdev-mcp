package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;

import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

final class JavacBatchParser {
    private JavacBatchParser() {
    }

    static ParsedBatch parse(IndexRequest request, ClassFileTypeCatalog catalog, CompilerClasspath classpath, SourceCorpus corpus, List<DecodedSource> batch, BiConsumer<JavacTask, Map<URI, CompilationUnitTree>> parsedUnitObserver) throws IndexBuildException, InterruptedException {
        request.cancellation().throwIfCancelled();
        JavaCompiler compiler = CompilerConfiguration.compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            StandardJavaFileManager standard = CompilerConfiguration.fileManager(compiler);
            try (MemorySourceFileManager manager = new MemorySourceFileManager(standard, corpus, classpath, request)) {
                List<JavaFileObject> explicit = batch.stream().map(manager::object).map(JavaFileObject.class::cast).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, compilerFileManager(manager), diagnostics, CompilerConfiguration.options(), null, explicit);
                Map<URI, CompilationUnitTree> parsedUnits = new LinkedHashMap<>();
                parsedUnitObserver.accept(task, parsedUnits);
                boolean finishAttempted = false;
                try {
                    List<? extends CompilationUnitTree> units = stream(task.parse());
                    JavacDiagnostics.failOnSyntaxErrors(diagnostics.getDiagnostics(), corpus);
                    Set<URI> ownedSources = batch.stream().map(DecodedSource::uri).collect(Collectors.toUnmodifiableSet());
                    Set<URI> explicitlyParsedSources = units.stream().map(unit -> unit.getSourceFile().toUri()).collect(Collectors.toUnmodifiableSet());
                    if (!explicitlyParsedSources.equals(ownedSources)) {
                        throw new IndexBuildException("Javac batch did not parse exactly its owned compilation units");
                    }
                    Map<ClassTree, List<? extends Tree>> declaredMembers = new IdentityHashMap<>();
                    Map<MethodTree, List<? extends VariableTree>> declaredMethodParameters = new IdentityHashMap<>();
                    Map<Tree, SourceRange> declaredRanges = new IdentityHashMap<>();
                    SourcePositions parsedPositions = Trees.instance(task).getSourcePositions();
                    for (CompilationUnitTree unit : units) {
                        for (Tree declaration : unit.getTypeDecls()) {
                            if (declaration instanceof ClassTree type) {
                                declaredMembers.put(type, List.copyOf(type.getMembers()));
                                JavacDeclarationReader.captureRange(unit, type, parsedPositions, declaredRanges);
                                for (Tree member : type.getMembers()) {
                                    JavacDeclarationReader.captureRange(unit, member, parsedPositions, declaredRanges);
                                    if (member instanceof MethodTree method) {
                                        List<? extends VariableTree> parameters = List.copyOf(method.getParameters());
                                        declaredMethodParameters.put(method, parameters);
                                        for (VariableTree parameter : parameters) {
                                            JavacDeclarationReader.captureRange(unit, parameter, parsedPositions, declaredRanges);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    task.analyze();
                    request.cancellation().throwIfCancelled();
                    Trees trees = Trees.instance(task);
                    TypeResolver resolver = new TypeResolver(task.getElements(), task.getTypes());
                    List<ParsedType> parsedTypes = new ArrayList<>();
                    for (CompilationUnitTree unit : units) {
                        DecodedSource source = corpus.require(unit.getSourceFile().toUri());
                        for (Tree declaration : unit.getTypeDecls()) {
                            if (declaration instanceof ClassTree type) {
                                parsedTypes.add(JavacDeclarationReader.parseType(unit, type, declaredMembers.getOrDefault(type, List.of()), declaredMethodParameters, declaredRanges, source, catalog, trees, resolver));
                            }
                        }
                    }
                    finishAttempted = true;
                    finish(task);
                    request.cancellation().throwIfCancelled();
                    Map<URI, List<OffsetRange>> executableBodies = new HashMap<>();
                    for (CompilationUnitTree unit : parsedUnits.values()) {
                        executableBodies.put(unit.getSourceFile().toUri(), new ExecutableBodyScanner(unit, trees.getSourcePositions()).scan());
                    }
                    return new ParsedBatch(parsedTypes, units.stream().map(unit -> corpus.require(unit.getSourceFile().toUri()).relativeName()).toList(), JavacDiagnostics.classifyDiagnostics(diagnostics.getDiagnostics(), corpus, executableBodies, ownedSources));
                } finally {
                    if (!finishAttempted && !Thread.currentThread().isInterrupted() && !request.cancellation().isCancelled()) {
                        finish(task);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IndexBuildException("Unable to configure isolated Javac worker", exception);
        }
    }

    static JavaFileManager compilerFileManager(MemorySourceFileManager manager) {
        return new ForwardingJavaFileManager<>(manager) {
            @Override
            public boolean contains(Location location, FileObject file) throws IOException {
                if (file instanceof MemorySourceFileObject) {
                    return location == StandardLocation.SOURCE_PATH;
                }
                return super.contains(location, file);
            }
        };
    }

    private static void finish(JavacTask task) throws IOException {
        stream(task.generate());
    }

    private static <T> List<T> stream(Iterable<? extends T> values) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }
}