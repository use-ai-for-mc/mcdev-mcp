package dev.mcdevmcp.storage.symbols;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Subprocess entry point for real multi-process lock, query, and recovery tests.
 */
final class IndexLockProcessMain {
    private IndexLockProcessMain() {
    }

    static void main(String[] arguments) throws Exception {
        Path database = Path.of(arguments[1]);
        switch (arguments[0]) {
            case "hold-read" -> {
                try (var lock = IndexLock.read(database, Duration.ofSeconds(5))) {
                    if (!lock.isHeld()) {
                        throw new AssertionError("read lock was not acquired");
                    }
                    System.out.println("locked");
                    System.out.flush();
                    awaitParentExit();
                }
            }
            case "query-and-close" -> {
                if (new SymbolRepository(database).classByName("marker.Marker") == null) {
                    throw new AssertionError("marker type missing");
                }
                System.out.println("closed");
                System.out.flush();
                awaitParentExit();
            }
            case "recover-failing" -> {
                try {
                    new AtomicSymbolIndex().rebuild(database, Duration.ofSeconds(1), _ -> {
                        throw new IOException("intentional rebuild failure");
                    }, _ -> {
                    });
                    throw new AssertionError("rebuild unexpectedly succeeded");
                } catch (IOException expected) {
                    System.out.println("recovered");
                    System.out.flush();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported process mode: " + arguments[0]);
        }
    }

    private static void awaitParentExit() throws IOException {
        int input;
        do {
            input = System.in.read();
        } while (input != -1);
    }
}
