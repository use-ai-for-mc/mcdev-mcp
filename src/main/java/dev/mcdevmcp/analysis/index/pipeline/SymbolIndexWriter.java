package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.PublishedSourceRoot;
import dev.mcdevmcp.storage.symbols.AtomicSymbolIndex;
import dev.mcdevmcp.storage.symbols.IndexValidator;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

value class SymbolIndexWriter {
    private final AtomicSymbolIndex databases;
    private final IndexValidator beforeValidation;

    SymbolIndexWriter() {
        this(new AtomicSymbolIndex(), _ -> {
        });
    }

    SymbolIndexWriter(AtomicSymbolIndex databases, IndexValidator beforeValidation) {
        this.databases = Objects.requireNonNull(databases, "databases");
        this.beforeValidation = Objects.requireNonNull(beforeValidation, "beforeValidation");
    }

    private static List<IndexedPackage> packages(List<ParsedType> types) {
        SortedSet<PackageIdentity> identities = new TreeSet<>();
        types.stream().map(PackageIdentity::new).forEach(identities::add);
        List<IndexedPackage> packages = new ArrayList<>(identities.size());
        long id = 1;
        for (PackageIdentity identity : identities) {
            packages.add(new IndexedPackage(id++, identity.namespace(), identity.fabricApiVersion(), identity.packageName()));
        }
        return List.copyOf(packages);
    }

    private static IndexCounts counts(List<IndexedPackage> packages, List<ParsedType> types) {
        int fields = types.stream().mapToInt(type -> type.fields().size()).sum();
        int methods = types.stream().mapToInt(type -> type.methods().size()).sum();
        int parameters = types.stream().flatMap(type -> type.methods().stream()).mapToInt(method -> method.parameters().size()).sum();
        return new IndexCounts(packages.size(), types.size(), fields, methods, parameters);
    }

    IndexCounts write(IndexRequest request, ParsedIndex index, String remappedJarSha256, Instant builtAt, PublishedSourceRoot publishedSourceRoot) throws Exception {
        List<IndexedPackage> packages = packages(index.types());
        IndexCounts counts = counts(packages, index.types());
        Instant persistedBuiltAt = builtAt.truncatedTo(ChronoUnit.MICROS);
        SymbolIndexSnapshot expected = SymbolIndexSnapshot.expected(request, remappedJarSha256, persistedBuiltAt, packages, index.types(), publishedSourceRoot);
        return databases.rebuild(request.outputIndex(), AtomicSymbolIndex.WRITE_LOCK_TIMEOUT, temporary -> {
            request.cancellation().throwIfCancelled();
            SymbolIndexFiles.write(temporary, expected.toData());
            beforeValidation.validate(temporary);
            return counts;
        }, SymbolIndexFiles::read, expected::validate);
    }
}
