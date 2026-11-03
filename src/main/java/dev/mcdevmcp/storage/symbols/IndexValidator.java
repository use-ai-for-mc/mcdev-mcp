package dev.mcdevmcp.storage.symbols;

import java.nio.file.Path;

@FunctionalInterface
public interface IndexValidator {
    void validate(Path file) throws Exception;
}
