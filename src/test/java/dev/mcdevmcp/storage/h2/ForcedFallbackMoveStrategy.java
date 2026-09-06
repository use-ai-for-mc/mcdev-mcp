package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.*;

final class ForcedFallbackMoveStrategy implements DatabaseFileOperations {
    private final int failingFallbackMove;
    private final FailureTiming failureTiming;
    private final DeleteFailure deleteFailure;
    private final Path companionBeforeFallback;
    private int fallbackMoves;

    ForcedFallbackMoveStrategy() {
        this(0, null, FailureTiming.BEFORE_SIDE_EFFECT, DeleteFailure.NONE);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove) {
        this(failingFallbackMove, null, FailureTiming.BEFORE_SIDE_EFFECT, DeleteFailure.NONE);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove, FailureTiming failureTiming) {
        this(failingFallbackMove, null, failureTiming, DeleteFailure.NONE);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove, FailureTiming failureTiming, DeleteFailure deleteFailure) {
        this(failingFallbackMove, null, failureTiming, deleteFailure);
    }

    ForcedFallbackMoveStrategy(Path companionBeforeFallback) {
        this(0, companionBeforeFallback, FailureTiming.BEFORE_SIDE_EFFECT, DeleteFailure.NONE);
    }

    private ForcedFallbackMoveStrategy(int failingFallbackMove, Path companionBeforeFallback, FailureTiming failureTiming, DeleteFailure deleteFailure) {
        this.failingFallbackMove = failingFallbackMove;
        this.companionBeforeFallback = companionBeforeFallback;
        this.failureTiming = failureTiming;
        this.deleteFailure = deleteFailure;
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
            if (companionBeforeFallback != null) {
                Files.writeString(companionBeforeFallback, "unsafe");
            }
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "forced by test");
        }
        fallbackMoves++;
        if (fallbackMoves == failingFallbackMove) {
            switch (failureTiming) {
                case BEFORE_SIDE_EFFECT -> {
                }
                case AFTER_SIDE_EFFECT -> Files.move(source, target, options);
                case AFTER_PARTIAL_COPY -> {
                    byte[] sourceBytes = Files.readAllBytes(source);
                    Files.write(target, java.util.Arrays.copyOf(sourceBytes, Math.max(1, sourceBytes.length / 2)));
                }
                case AFTER_SOURCE_REMOVAL -> Files.delete(source);
            }
            throw new IOException("forced fallback move failure " + fallbackMoves);
        }
        Files.move(source, target, options);
    }

    @Override
    public void delete(Path path) throws IOException {
        failDelete(path);
        DatabaseFileOperations.super.delete(path);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        failDelete(path);
        return DatabaseFileOperations.super.deleteIfExists(path);
    }

    private void failDelete(Path path) throws IOException {
        if (deleteFailure == DeleteFailure.ANY || deleteFailure == DeleteFailure.FAILED_PROMOTION && path.getFileName().toString().endsWith(".failed-promotion")) {
            throw new IOException("forced delete failure: " + path);
        }
    }

    enum FailureTiming {
        BEFORE_SIDE_EFFECT, AFTER_SIDE_EFFECT, AFTER_PARTIAL_COPY, AFTER_SOURCE_REMOVAL
    }

    enum DeleteFailure {
        NONE, ANY, FAILED_PROMOTION
    }
}
