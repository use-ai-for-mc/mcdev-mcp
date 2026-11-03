package dev.mcdevmcp.storage.symbols;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

final class ForcedFallbackMoveStrategy implements IndexFileOperations {
    private final int failingFallbackMove;
    private final FailureTiming failureTiming;
    private final DeleteFailure deleteFailure;
    private int fallbackMoves;

    ForcedFallbackMoveStrategy() {
        this(0, FailureTiming.BEFORE_SIDE_EFFECT, DeleteFailure.NONE);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove) {
        this(failingFallbackMove, FailureTiming.BEFORE_SIDE_EFFECT, DeleteFailure.NONE);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove, FailureTiming failureTiming) {
        this(failingFallbackMove, failureTiming, DeleteFailure.NONE);
    }

    ForcedFallbackMoveStrategy(int failingFallbackMove, FailureTiming failureTiming, DeleteFailure deleteFailure) {
        this.failingFallbackMove = failingFallbackMove;
        this.failureTiming = failureTiming;
        this.deleteFailure = deleteFailure;
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
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
                    Files.write(target, Arrays.copyOf(sourceBytes, Math.max(1, sourceBytes.length / 2)));
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
        IndexFileOperations.super.delete(path);
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
