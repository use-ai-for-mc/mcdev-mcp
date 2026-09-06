package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class PausingDatabaseFileOperations implements DatabaseFileOperations {
    private final CountDownLatch deletionStarted = new CountDownLatch(1);
    private final CountDownLatch continueDeletion = new CountDownLatch(1);
    private final AtomicBoolean pauseNextDeletion = new AtomicBoolean(true);

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        Files.move(source, target, options);
    }

    @Override
    public void delete(Path path) throws IOException {
        if (pauseNextDeletion.compareAndSet(true, false)) {
            deletionStarted.countDown();
            try {
                if (!continueDeletion.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to continue test deletion");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to continue test deletion", exception);
            }
        }
        DatabaseFileOperations.super.delete(path);
    }

    boolean awaitDeletion() throws InterruptedException {
        return deletionStarted.await(5, TimeUnit.SECONDS);
    }

    void continueDeletion() {
        continueDeletion.countDown();
    }
}
