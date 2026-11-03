package dev.mcdevmcp.storage.symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class AtomicSymbolIndex {
    public static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(30);
    private final SymbolIndexPromotion promotion;

    public AtomicSymbolIndex() {
        this(Files::move);
    }

    AtomicSymbolIndex(IndexFileOperations files) {
        promotion = new SymbolIndexPromotion(Objects.requireNonNull(files, "files"));
    }

    public <T> T rebuild(Path target, Duration lockTimeout, IndexBuilder<T> builder, IndexValidator validator) throws IOException {
        return rebuild(target, lockTimeout, builder, validator, validator);
    }

    public <T> T rebuild(Path target, Duration lockTimeout, IndexBuilder<T> builder, IndexValidator existingTargetValidator, IndexValidator candidateValidator) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(existingTargetValidator, "existingTargetValidator");
        Objects.requireNonNull(candidateValidator, "candidateValidator");
        SymbolIndexArtifacts artifacts = new SymbolIndexArtifacts(target);
        artifacts.createTargetDirectory();
        try (var databaseLock = IndexLock.write(artifacts.target, lockTimeout)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock");
            }
            promotion.resolveBackup(artifacts, existingTargetValidator);
            artifacts.deleteTemporary();
            try {
                T result = builder.build(artifacts.temporary);
                SymbolIndexPromotion.validate(artifacts.temporary, candidateValidator);
                promotion.promote(artifacts, candidateValidator);
                return result;
            } catch (Exception exception) {
                try {
                    artifacts.deleteTemporary();
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Symbol index rebuild failed", exception);
            }
        }
    }
}
