package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

final class JavacSourceParser {
    static final int MAXIMUM_BATCH_UNITS = 512;
    static final int MAXIMUM_BATCH_CHARACTERS = 4_194_304;
    private final Runnable compilerStarted;
    private final AtomicBoolean compilerStartedNotified = new AtomicBoolean();

    JavacSourceParser() {
        this(() -> {
        });
    }

    JavacSourceParser(Runnable compilerStarted) {
        this.compilerStarted = Objects.requireNonNull(compilerStarted, "compilerStarted");
    }

    ParsedIndex parse(IndexRequest request, ClassFileTypeCatalog catalog, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        if (discovered.sources().isEmpty()) {
            return new ParsedIndex(List.of(), List.of(), List.of());
        }
        SourceCorpus corpus = preflight(request, classpath, discovered);
        List<List<DecodedSource>> batches = partition(corpus.sources());
        int workerCount = Math.min(batches.size(), Math.min(request.threads(), Runtime.getRuntime().availableProcessors()));
        List<Callable<ParsedBatch>> tasks = new ArrayList<>();
        for (List<DecodedSource> batch : batches) {
            tasks.add(() -> JavacBatchParser.parse(request, catalog, classpath, corpus, batch, this::observeParsedUnits));
        }
        return JavacTaskExecutor.executeAll(request, workerCount, tasks, this::assembleParsedIndex);
    }

    static List<List<DecodedSource>> partition(List<DecodedSource> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        boolean hasModuleDescriptor = sources.stream().map(DecodedSource::relativePath).map(Path::getFileName).anyMatch(Path.of("module-info.java")::equals);
        if (hasModuleDescriptor) {
            return List.of(List.copyOf(sources));
        }
        List<List<DecodedSource>> batches = new ArrayList<>();
        List<DecodedSource> batch = new ArrayList<>();
        long characters = 0;
        for (DecodedSource source : sources) {
            int length = source.content().length();
            if (!batch.isEmpty() && (batch.size() == MAXIMUM_BATCH_UNITS || characters + length > MAXIMUM_BATCH_CHARACTERS)) {
                batches.add(List.copyOf(batch));
                batch.clear();
                characters = 0;
            }
            batch.add(source);
            characters += length;
        }
        batches.add(List.copyOf(batch));
        return List.copyOf(batches);
    }

    private ParsedIndex assembleParsedIndex(List<ParsedBatch> batches) {
        List<ParsedType> types = new ArrayList<>();
        List<String> units = new ArrayList<>();
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        for (ParsedBatch batch : batches) {
            types.addAll(batch.types());
            units.addAll(batch.parsedCompilationUnits());
            diagnostics.addAll(batch.diagnostics());
        }
        return new ParsedIndex(types, units, diagnostics);
    }

    private SourceCorpus preflight(IndexRequest request, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        return JavacTaskExecutor.executeSingle(request, () -> JavacPreflight.preflight(request, classpath, discovered, this::observeParsedUnits));
    }

    private void observeParsedUnits(JavacTask task, Map<URI, CompilationUnitTree> parsedUnits) {
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.PARSE && event.getCompilationUnit() != null) {
                    parsedUnits.putIfAbsent(event.getCompilationUnit().getSourceFile().toUri(), event.getCompilationUnit());
                    if (compilerStartedNotified.compareAndSet(false, true)) {
                        compilerStarted.run();
                    }
                }
            }
        });
    }
}
