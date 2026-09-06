package dev.mcdevmcp.storage.h2;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Subprocess entry point for real multi-process lock, query, and recovery tests.
 */
@SuppressWarnings("JavaPrintToLogpoint")
final class DatabaseLockProcessMain {
    private DatabaseLockProcessMain() {
    }

    static void main(String[] arguments) throws Exception {
        Path database = Path.of(arguments[1]);
        switch (arguments[0]) {
            case "hold-read" -> {
                try (var lock = DatabaseLock.read(database, Duration.ofSeconds(5))) {
                    if (!lock.isHeld()) {
                        throw new AssertionError("read lock was not acquired");
                    }
                    System.out.println("locked");
                    System.out.flush();
                    awaitParentExit();
                }
            }
            case "query-and-close" -> {
                var repository = new SymbolRepository(database);
                assertEquals("value", repository.query(connection -> {
                    try (var statement = connection.createStatement()) {
                        //noinspection SqlNoDataSourceInspection,SqlResolve
                        try (var results = statement.executeQuery("SELECT marker_value FROM marker")) {
                            if (!results.next()) {
                                throw new java.sql.SQLException("marker missing");
                            }
                            return results.getString(1);
                        }
                    }
                }));
                System.out.println("closed");
                System.out.flush();
                awaitParentExit();
            }
            case "recover-failing" -> {
                try {
                    new AtomicH2Database().rebuild(database, Duration.ofSeconds(1), _ -> {
                        throw new java.sql.SQLException("intentional rebuild failure");
                    }, _ -> {
                    });
                    throw new AssertionError("rebuild unexpectedly succeeded");
                } catch (java.sql.SQLException expected) {
                    System.out.println("recovered");
                    System.out.flush();
                }
            }
            case "hold-h2-read" -> holdH2(H2DatabaseUrls.reader(database));
            case "hold-h2-write" -> holdH2(H2DatabaseUrls.writer(database));
            case "try-h2-write" -> {
                try (var connection = DriverManager.getConnection(shortLockTimeout(H2DatabaseUrls.writer(database)))) {
                    if (connection.isClosed()) {
                        throw new AssertionError("H2 connection unexpectedly closed");
                    }
                    System.out.println("opened");
                } catch (java.sql.SQLException expected) {
                    System.out.println("blocked");
                }
                System.out.flush();
            }
            default -> throw new IllegalArgumentException("Unsupported process mode: " + arguments[0]);
        }
    }

    private static void holdH2(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url)) {
            if (connection.isClosed()) {
                throw new AssertionError("H2 connection unexpectedly closed");
            }
            System.out.println("h2-open");
            System.out.flush();
            awaitParentExit();
        }
    }

    private static String shortLockTimeout(String url) {
        return url.replace("LOCK_TIMEOUT=30000", "LOCK_TIMEOUT=250");
    }

    private static void awaitParentExit() throws java.io.IOException {
        int input;
        do {
            input = System.in.read();
        } while (input != -1);
    }
}
