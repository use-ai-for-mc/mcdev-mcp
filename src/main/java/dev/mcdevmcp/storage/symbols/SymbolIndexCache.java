package dev.mcdevmcp.storage.symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the most recently used symbol indexes loaded and reloads one when its file is replaced.
 */
final class SymbolIndexCache {
    private static final SymbolIndexCache SHARED = new SymbolIndexCache(2);
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>(4, 0.75f, true);

    SymbolIndexCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    static SymbolIndexCache shared() {
        return SHARED;
    }

    SymbolIndex load(Path file) throws IOException {
        Path key = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        lock.lock();
        try {
            BasicFileAttributes attributes = Files.readAttributes(key, BasicFileAttributes.class);
            Identity identity = new Identity(SymbolIndexFiles.readHeader(key), attributes.size(), attributes.lastModifiedTime());
            Entry entry = entries.get(key);
            if (entry != null && entry.identity().equals(identity)) {
                return entry.index();
            }
            SymbolIndex index = index(key);
            entries.put(key, new Entry(identity, index));
            var eldest = entries.keySet().iterator();
            while (entries.size() > capacity) {
                eldest.next();
                eldest.remove();
            }
            return index;
        } finally {
            lock.unlock();
        }
    }

    private static SymbolIndex index(Path file) throws IOException {
        SymbolIndexData data = SymbolIndexFiles.read(file);
        try {
            return SymbolIndex.of(data);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Symbol index contains invalid rows: " + file, exception);
        }
    }

    private record Identity(SymbolIndexData.Header header, long size, FileTime modified) {
    }

    private record Entry(Identity identity, SymbolIndex index) {
    }
}
