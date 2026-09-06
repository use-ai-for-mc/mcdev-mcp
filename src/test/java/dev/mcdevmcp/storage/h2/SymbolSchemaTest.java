package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.PlatformPaths;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

//noinspection SqlNoDataSourceInspection,SqlResolve
@SuppressWarnings("SqlNoDataSourceInspection") // Each test creates an isolated database.
class SymbolSchemaTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private static final String DROP_METHODS_INDEX_SQL = "DROP INDEX idx_methods_type_name";
    private static final String WEAK_PACKAGE_IDENTITY_SQL = "ALTER TABLE packages ADD CONSTRAINT ck_packages_source_identity CHECK (TRUE)";

    @TempDir
    Path temporaryDirectory;

    private static void assertRejectedByValidationAndState(PlatformPaths paths) {
        Path database = paths.symbolDatabase(VERSION);
        assertThrows(SQLException.class, () -> new SymbolRepository(database).query(connection -> {
            SymbolSchema.validate(connection);
            return null;
        }));
        assertFalse(new VersionStateRepository(paths).isH2Ready(VERSION));
    }

    private static String packageIdentityConstraint(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(sql("SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name WHERE tc.table_schema = 'PUBLIC' AND tc.table_name = 'PACKAGES' AND tc.constraint_type = 'CHECK'"))) {
            while (results.next()) {
                if (results.getString("check_clause").toUpperCase(java.util.Locale.ROOT).contains("FABRIC_API_VERSION")) {
                    return results.getString("constraint_name");
                }
            }
        }
        throw new AssertionError("Package source identity constraint not found");
    }

    private static Set<String> tableNames(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'"))) {
            var names = new java.util.HashSet<String>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return Set.copyOf(names);
        }
    }

    private static Set<String> columnSignatures(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(sql("SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'PUBLIC' ORDER BY table_name, ordinal_position"))) {
            var signatures = new java.util.HashSet<String>();
            while (results.next()) {
                signatures.add(results.getString("table_name") + "|" + results.getString("column_name") + "|" + results.getString("data_type") + "|" + results.getString("is_nullable"));
            }
            return Set.copyOf(signatures);
        }
    }

    private static Set<String> constraintNames(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(sql("SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = 'PUBLIC'"))) {
            var names = new java.util.HashSet<String>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return Set.copyOf(names);
        }
    }

    private static Set<String> secondaryIndexNames(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var results = statement.executeQuery(sql("SELECT index_name FROM information_schema.indexes WHERE table_schema = 'PUBLIC' AND index_name LIKE 'IDX_%'"))) {
            var names = new java.util.HashSet<String>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return Set.copyOf(names);
        }
    }

    private static Set<String> expectedConstraintNames() {
        return Set.of("PK_METADATA", "CK_METADATA_SINGLETON", "PK_PACKAGES", "CK_PACKAGES_SOURCE_IDENTITY", "UQ_PACKAGES_SOURCE_NAME", "UQ_PACKAGES_IDENTITY_REFERENCE", "PK_TYPES", "CK_TYPES_SOURCE_IDENTITY", "CK_TYPES_KIND", "UQ_TYPES_BINARY_NAME", "FK_TYPES_PACKAGE_IDENTITY", "PK_TYPE_INTERFACES", "FK_TYPE_INTERFACES_TYPE", "PK_FIELDS", "UQ_FIELDS_TYPE_ORDINAL", "FK_FIELDS_TYPE", "PK_METHODS", "UQ_METHODS_TYPE_ORDINAL", "FK_METHODS_TYPE", "PK_PARAMETERS", "UQ_PARAMETERS_METHOD_ORDINAL", "FK_PARAMETERS_METHOD");
    }

    private static Set<String> expectedColumnSignatures() {
        return Set.of("METADATA|SINGLETON|BOOLEAN|NO", "METADATA|SCHEMA_VERSION|INTEGER|NO", "METADATA|MINECRAFT_VERSION|CHARACTER VARYING|NO", "METADATA|SOURCE_ROOT|CHARACTER VARYING|NO", "METADATA|REMAPPED_JAR_SHA256|CHARACTER VARYING|NO", "METADATA|BUILT_AT|TIMESTAMP WITH TIME ZONE|NO", "PACKAGES|ID|BIGINT|NO", "PACKAGES|SOURCE_NAMESPACE|CHARACTER VARYING|NO", "PACKAGES|FABRIC_API_VERSION|CHARACTER VARYING|YES", "PACKAGES|FABRIC_API_VERSION_KEY|CHARACTER VARYING|NO", "PACKAGES|NAME|CHARACTER VARYING|NO", "TYPES|ID|BIGINT|NO", "TYPES|PACKAGE_ID|BIGINT|NO", "TYPES|SOURCE_NAMESPACE|CHARACTER VARYING|NO", "TYPES|FABRIC_API_VERSION|CHARACTER VARYING|YES", "TYPES|FABRIC_API_VERSION_KEY|CHARACTER VARYING|NO", "TYPES|BINARY_NAME|CHARACTER VARYING|NO", "TYPES|SIMPLE_NAME|CHARACTER VARYING|NO", "TYPES|KIND|CHARACTER VARYING|NO", "TYPES|SUPERCLASS_BINARY_NAME|CHARACTER VARYING|YES", "TYPES|SOURCE_PATH|CHARACTER VARYING|NO", "TYPES|START_OFFSET|INTEGER|NO", "TYPES|END_OFFSET|INTEGER|NO", "TYPES|START_LINE|INTEGER|NO", "TYPES|END_LINE|INTEGER|NO", "TYPE_INTERFACES|TYPE_ID|BIGINT|NO", "TYPE_INTERFACES|ORDINAL|INTEGER|NO", "TYPE_INTERFACES|INTERFACE_BINARY_NAME|CHARACTER VARYING|NO", "FIELDS|ID|BIGINT|NO", "FIELDS|TYPE_ID|BIGINT|NO", "FIELDS|ORDINAL|INTEGER|NO", "FIELDS|NAME|CHARACTER VARYING|NO", "FIELDS|TYPE|CHARACTER VARYING|NO", "FIELDS|MODIFIERS|CHARACTER VARYING|NO", "FIELDS|START_OFFSET|INTEGER|NO", "FIELDS|END_OFFSET|INTEGER|NO", "FIELDS|START_LINE|INTEGER|NO", "FIELDS|END_LINE|INTEGER|NO", "METHODS|ID|BIGINT|NO", "METHODS|TYPE_ID|BIGINT|NO", "METHODS|ORDINAL|INTEGER|NO", "METHODS|NAME|CHARACTER VARYING|NO", "METHODS|DESCRIPTOR|CHARACTER VARYING|NO", "METHODS|RETURN_TYPE|CHARACTER VARYING|YES", "METHODS|MODIFIERS|CHARACTER VARYING|NO", "METHODS|CONSTRUCTOR|BOOLEAN|NO", "METHODS|START_OFFSET|INTEGER|NO", "METHODS|END_OFFSET|INTEGER|NO", "METHODS|START_LINE|INTEGER|NO", "METHODS|END_LINE|INTEGER|NO", "PARAMETERS|ID|BIGINT|NO", "PARAMETERS|METHOD_ID|BIGINT|NO", "PARAMETERS|ORDINAL|INTEGER|NO", "PARAMETERS|NAME|CHARACTER VARYING|NO", "PARAMETERS|TYPE|CHARACTER VARYING|NO", "PARAMETERS|VARARGS|BOOLEAN|NO", "PARAMETERS|START_OFFSET|INTEGER|NO", "PARAMETERS|END_OFFSET|INTEGER|NO", "PARAMETERS|START_LINE|INTEGER|NO", "PARAMETERS|END_LINE|INTEGER|NO");
    }

    private static void assertFails(Connection connection, String sql) {
        assertThrows(java.sql.SQLException.class, () -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        });
    }

    private static String sql(String statement) {
        return statement;
    }

    @Test
    void createsTypedH2MetadataAndNormalizedSchema() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        Path sourceRoot = Path.of("/sources/client");
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            SymbolSchema.create(connection, VERSION, sourceRoot, "a".repeat(64), Instant.parse("2026-07-12T12:00:00Z"));
            SymbolSchema.createIndexes(connection);
            SymbolSchema.validate(connection);

            assertEquals(Set.of("METADATA", "PACKAGES", "TYPES", "TYPE_INTERFACES", "FIELDS", "METHODS", "PARAMETERS"), tableNames(connection));
            assertEquals(expectedColumnSignatures(), columnSignatures(connection));
            assertEquals(expectedConstraintNames(), constraintNames(connection));
            assertEquals(Set.of("IDX_TYPE_BINARY_NAME", "IDX_TYPES_PACKAGE_NAME", "IDX_FIELDS_TYPE_NAME", "IDX_METHODS_TYPE_NAME", "IDX_TYPE_INTERFACES_BINARY_NAME"), secondaryIndexNames(connection));
            try (var statement = connection.createStatement();
                 var results = statement.executeQuery(sql("SELECT schema_version, minecraft_version, source_root, remapped_jar_sha256, built_at FROM metadata"))) {
                assertTrue(results.next());
                assertEquals(1, results.getInt("schema_version"));
                assertEquals("1.21.5", results.getString("minecraft_version"));
                assertEquals(sourceRoot.toString(), results.getString("source_root"));
                assertEquals("a".repeat(64), results.getString("remapped_jar_sha256"));
                assertEquals(Instant.parse("2026-07-12T12:00:00Z"), results.getObject("built_at", java.time.OffsetDateTime.class).toInstant());
            }
        }
    }

    @Test
    void enforcesWireKindsNamespacesBooleansAndForeignKeys() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            SymbolSchema.create(connection, VERSION, Path.of("/sources/client"), "b".repeat(64), Instant.now());
            SymbolSchema.createIndexes(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql("INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('minecraft', NULL, 'net.minecraft')"));
                assertFails(connection, "INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('minecraft', NULL, 'net.minecraft')");
                assertFails(connection, "INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('fabric', NULL, 'net.fabricmc')");
                assertFails(connection, "INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('fabric', '   ', 'net.fabricmc.blank')");
                statement.executeUpdate(sql("INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('fabric', '0.120.0', 'net.fabricmc')"));
                statement.executeUpdate(sql("INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', NULL, 'net.minecraft.Test', 'Test', 'class', 'Test.java', 0, 10, 1, 1)"));
                statement.executeUpdate(sql("INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES ((SELECT id FROM packages WHERE name = 'net.fabricmc'), 'fabric', '0.120.0', 'net.fabricmc.Test', 'Test', 'class', 'FabricTest.java', 0, 10, 1, 1)"));
                statement.executeUpdate(sql("INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'field', 'int', 'public', 0, 1, 1, 1)"));
                statement.executeUpdate(sql("INSERT INTO methods(type_id, ordinal, name, descriptor, modifiers, constructor, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'method', '()V', 'public', TRUE, 0, 1, 1, 1)"));
            }
            SymbolSchema.validate(connection);
            assertFails(connection, "INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('minecraft', '0.120.0', 'bad')");
            assertFails(connection, "INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES ((SELECT id FROM packages WHERE name = 'net.fabricmc'), 'fabric', '   ', 'bad.BlankFabricVersion', 'BlankFabricVersion', 'class', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES ((SELECT id FROM packages WHERE name = 'net.minecraft'), 'fabric', '0.120.0', 'bad.FabricUnderMinecraft', 'FabricUnderMinecraft', 'class', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES ((SELECT id FROM packages WHERE name = 'net.fabricmc'), 'minecraft', NULL, 'bad.MinecraftUnderFabric', 'MinecraftUnderFabric', 'class', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES ((SELECT id FROM packages WHERE name = 'net.fabricmc'), 'fabric', '0.121.0', 'bad.WrongFabricVersion', 'WrongFabricVersion', 'class', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', NULL, 'net.minecraft.Bad', 'Bad', 'not-a-kind', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'again', 'int', 'public', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (99, 1, 'orphan', 'int', 'public', 0, 1, 1, 1)");
        }
    }

    @Test
    void reopenedValidationAndVersionStateRejectMissingSecondaryIndex() throws Exception {
        var paths = createReadyDatabase();
        Path database = paths.symbolDatabase(VERSION);
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database));
             var statement = connection.createStatement()) {
            statement.execute(DROP_METHODS_INDEX_SQL);
        }

        assertRejectedByValidationAndState(paths);
    }

    @Test
    void reopenedValidationAndVersionStateRejectWeakenedIdentityConstraint() throws Exception {
        var paths = createReadyDatabase();
        Path database = paths.symbolDatabase(VERSION);
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database));
             var statement = connection.createStatement()) {
            String identityConstraint = packageIdentityConstraint(connection);
            statement.execute("ALTER TABLE packages DROP CONSTRAINT " + identityConstraint);
            statement.execute(WEAK_PACKAGE_IDENTITY_SQL);
        }

        assertRejectedByValidationAndState(paths);
    }

    private PlatformPaths createReadyDatabase() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolDatabase(VERSION);
        java.nio.file.Files.createDirectories(database.getParent());
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            SymbolSchema.create(connection, VERSION, paths.sourceRoot(VERSION), "c".repeat(64), Instant.now());
            SymbolSchema.createIndexes(connection);
        }
        assertEquals(dev.mcdevmcp.storage.model.VersionState.READY, new VersionStateRepository(paths).state(VERSION));
        return paths;
    }
}
