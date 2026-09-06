package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.IndexBuildEvidence;
import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.IndexSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SourceIndexPipeline {
    private static final int REPORTED_DIAGNOSTIC_LIMIT = 20;
    private static final int REPORTED_DIAGNOSTIC_CHARACTERS = 512;

    private final JavacSourceParser parser;
    private final SymbolIndexWriter writer;
    private final Runnable beforeEvidence;

    public SourceIndexPipeline() {
        this(new JavacSourceParser(), new SymbolIndexWriter());
    }

    SourceIndexPipeline(JavacSourceParser parser, SymbolIndexWriter writer) {
        this(parser, writer, () -> {
        });
    }

    SourceIndexPipeline(JavacSourceParser parser, SymbolIndexWriter writer, Runnable beforeEvidence) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.beforeEvidence = Objects.requireNonNull(beforeEvidence, "beforeEvidence");
    }

    private static void validateInputs(IndexRequest request) throws IOException {
        requireRegularFile(request.remappedJar(), "Remapped JAR");
        for (Path entry : request.classpath()) {
            requireRegularFile(entry, "Classpath entry");
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular file: " + path);
        }
    }

    private static void rejectDuplicateBinaryNames(ParsedIndex parsed) throws IndexBuildException {
        Set<String> names = new HashSet<>();
        for (ParsedType type : parsed.types()) {
            if (!names.add(type.binaryName())) {
                throw new IndexBuildException("Duplicate parsed binary name " + type.binaryName());
            }
        }
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String reportedDiagnostic(IndexDiagnostic diagnostic) {
        String display = diagnostic.display().replace('\r', ' ').replace('\n', ' ');
        if (display.length() <= REPORTED_DIAGNOSTIC_CHARACTERS) {
            return display;
        }
        String suffix = "... [" + diagnostic.code() + "]";
        return display.substring(0, REPORTED_DIAGNOSTIC_CHARACTERS - suffix.length()) + suffix;
    }

    public IndexSummary build(IndexRequest request) throws IndexBuildException {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        try {
            request.progress().report("index", 0, "Discovering and decoding Java sources");
            SourceCorpus corpus = SourceCorpus.discover(request.sourceRoots(), request.cancellation());
            request.cancellation().throwIfCancelled();
            validateInputs(request);
            request.progress().report("index", 15, "Reading remapped class-file catalog");
            ClassFileTypeCatalog catalog = ClassFileTypeCatalog.read(request.remappedJar(), request.cancellation());
            CompilerClasspath classpath = CompilerClasspath.read(request);
            request.progress().report("index", 30, "Parsing and attributing Java sources with Javac");
            ParsedIndex parsed = parser.parse(request, catalog, classpath, corpus);
            rejectDuplicateBinaryNames(parsed);
            List<IndexDiagnostic> diagnosticsToReport = parsed.diagnostics().stream().limit(REPORTED_DIAGNOSTIC_LIMIT).toList();
            for (IndexDiagnostic diagnostic : diagnosticsToReport) {
                request.progress().report("index", 70, reportedDiagnostic(diagnostic));
            }
            if (parsed.diagnostics().size() > diagnosticsToReport.size()) {
                request.progress().report("index", 70, "Retained " + parsed.diagnostics().size() + " Javac diagnostics; showing the first " + diagnosticsToReport.size());
            }
            request.cancellation().throwIfCancelled();
            String remappedJarSha256 = sha256(request.remappedJar());
            beforeEvidence.run();
            var discovered = corpus.sources().stream().map(DecodedSource::relativeName).toList();
            var parsedUnits = parsed.parsedCompilationUnits();
            var typed = parsed.types().stream().map(type -> new PortablePath(type.sourcePath()).value()).distinct().toList();
            var typeFree = parsedUnits.stream().filter(path -> !typed.contains(path)).toList();
            var diagnostics = parsed.diagnostics().stream().map(IndexDiagnostic::display).toList();
            IndexBuildEvidence evidence = new IndexBuildEvidence(discovered, parsedUnits, typed, typeFree, diagnostics);
            request.progress().report("index", 75, "Writing validated symbol database");
            IndexCounts counts = writer.write(request, parsed, remappedJarSha256, Instant.now());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            request.progress().report("index", 100, "Indexed " + counts.types() + " Java types");
            return new IndexSummary(counts.packages(), counts.types(), counts.fields(), counts.methods(), counts.parameters(), elapsed, evidence);
        } catch (IndexBuildException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            if (!request.cancellation().isCancelled()) {
                Thread.currentThread().interrupt();
            }
            throw new IndexBuildException("Java source index build cancelled", exception);
        } catch (Exception exception) {
            throw new IndexBuildException("Java source index build failed: " + exception.getMessage(), exception);
        }
    }
}
