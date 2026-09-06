package dev.mcdevmcp.packaging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ServiceLoader;

@SuppressWarnings("JavaPrintToLogpoint")
final class H2ServiceLoaderProbeMain {
    private H2ServiceLoaderProbeMain() {
    }

    @SuppressWarnings("SqlNoDataSourceInspection") // The probe creates its own temporary database.
    static void main(String[] arguments) throws Exception {
        Path databaseBase = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path expectedJar = Path.of(arguments[1]).toRealPath();
        var h2Drivers = ServiceLoader.load(Driver.class).stream().map(ServiceLoader.Provider::get).filter(driver -> driver.getClass().getName().equals("org.h2.Driver")).toList();
        if (h2Drivers.size() != 1) {
            throw new AssertionError("Expected exactly one H2 JDBC service, found " + h2Drivers);
        }
        Path driverJar = Path.of(h2Drivers.getFirst().getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!driverJar.equals(expectedJar)) {
            throw new AssertionError("H2 JDBC service loaded from " + driverJar + " instead of " + expectedJar);
        }

        String url = "jdbc:h2:file:" + databaseBase + ";DB_CLOSE_ON_EXIT=FALSE;FILE_LOCK=FS;WRITE_DELAY=0;LOCK_TIMEOUT=30000;TRACE_LEVEL_FILE=0";
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE smoke (id INTEGER PRIMARY KEY)");
            statement.executeUpdate(insertSql());
            try (var results = statement.executeQuery(selectSql())) {
                if (!results.next() || results.getInt(1) != 1 || results.next()) {
                    throw new AssertionError("Unexpected H2 smoke query result");
                }
            }
            statement.execute(checkpointSql());
        }

        Path database = databaseBase.resolveSibling(databaseBase.getFileName() + ".mv.db");
        Path renamed = database.resolveSibling(database.getFileName() + ".renamed");
        Files.move(database, renamed, StandardCopyOption.ATOMIC_MOVE);
        Files.move(renamed, database, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("H2_SERVICE_OK");
    }

    @SuppressWarnings("SameReturnValue")
    private static String insertSql() {
        return "INSERT INTO smoke(id) VALUES (1)";
    }

    @SuppressWarnings("SameReturnValue")
    private static String selectSql() {
        return "SELECT id FROM smoke";
    }

    @SuppressWarnings("SameReturnValue")
    private static String checkpointSql() {
        return "CHECKPOINT SYNC";
    }
}
