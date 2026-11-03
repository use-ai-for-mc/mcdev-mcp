package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic, read-only access to a published symbol index; every call sees the file as currently published.
 */
public final class SymbolRepository {
    private final Path database;

    public SymbolRepository(Path database) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
    }

    private SymbolIndex index() throws IOException {
        try (var databaseLock = IndexLock.read(database, AtomicSymbolIndex.WRITE_LOCK_TIMEOUT)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire shared database lock");
            }
            return SymbolIndexCache.shared().load(database);
        }
    }

    public List<ClassSymbol> classesUnder(String packageName, int limitPlusOne) throws IOException {
        return index().classesUnder(packageName, limitPlusOne);
    }

    public PackageListing packages(String namespace, int limitPlusOne) throws IOException {
        return index().packages(namespace, limitPlusOne);
    }

    public ClassSymbol classByName(String name) throws IOException {
        return index().classByName(name);
    }

    public Optional<ClassSymbol> enclosingType(ClassSymbol symbol) throws IOException {
        return index().enclosingType(symbol);
    }

    public List<ClassSymbol> memberTypes(String binaryName) throws IOException {
        return index().memberTypes(binaryName);
    }

    public List<ClassSymbol> classesNamedLike(String name, int limit) throws IOException {
        return index().classesNamedLike(name, limit);
    }

    public List<FieldSymbol> fields(long typeId) throws IOException {
        return index().fields(typeId);
    }

    public List<MethodSymbol> methods(long typeId) throws IOException {
        return index().methods(typeId);
    }

    public List<MethodSymbol> methodsNamed(long typeId, String name) throws IOException {
        return index().methodsNamed(typeId, name);
    }

    public List<ParameterSymbol> parameters(long methodId) throws IOException {
        return index().parameters(methodId);
    }

    public List<ClassSymbol> hierarchy(String binaryName, boolean subclasses, int limitPlusOne) throws IOException {
        return index().hierarchy(binaryName, subclasses, limitPlusOne);
    }

    /**
     * Literal, case-insensitive matching in Node's namespace/package/type/member order.
     */
    public List<SearchHit> search(String query, String kind, int limitPlusOne) throws IOException {
        return index().search(query, kind, limitPlusOne);
    }
}
