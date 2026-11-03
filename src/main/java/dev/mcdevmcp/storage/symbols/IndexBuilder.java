package dev.mcdevmcp.storage.symbols;

import java.nio.file.Path;

@FunctionalInterface
public interface IndexBuilder<T> {
    T build(Path temporary) throws Exception;
}
