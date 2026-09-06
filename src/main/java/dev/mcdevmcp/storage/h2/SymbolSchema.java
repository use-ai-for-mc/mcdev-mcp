package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@SuppressWarnings("SqlNoDataSourceInspection")
//noinspection SqlResolve
public final class SymbolSchema {
    public static final int VERSION = 1;
    private static final Set<String> TABLES = Set.of("METADATA", "PACKAGES", "TYPES", "TYPE_INTERFACES", "FIELDS", "METHODS", "PARAMETERS");
    private static final Set<String> COLUMN_SIGNATURES = Set.of("METADATA|SINGLETON|BOOLEAN|NO", "METADATA|SCHEMA_VERSION|INTEGER|NO", "METADATA|MINECRAFT_VERSION|CHARACTER VARYING|NO", "METADATA|SOURCE_ROOT|CHARACTER VARYING|NO", "METADATA|REMAPPED_JAR_SHA256|CHARACTER VARYING|NO", "METADATA|BUILT_AT|TIMESTAMP WITH TIME ZONE|NO", "PACKAGES|ID|BIGINT|NO", "PACKAGES|SOURCE_NAMESPACE|CHARACTER VARYING|NO", "PACKAGES|FABRIC_API_VERSION|CHARACTER VARYING|YES", "PACKAGES|FABRIC_API_VERSION_KEY|CHARACTER VARYING|NO", "PACKAGES|NAME|CHARACTER VARYING|NO", "TYPES|ID|BIGINT|NO", "TYPES|PACKAGE_ID|BIGINT|NO", "TYPES|SOURCE_NAMESPACE|CHARACTER VARYING|NO", "TYPES|FABRIC_API_VERSION|CHARACTER VARYING|YES", "TYPES|FABRIC_API_VERSION_KEY|CHARACTER VARYING|NO", "TYPES|BINARY_NAME|CHARACTER VARYING|NO", "TYPES|SIMPLE_NAME|CHARACTER VARYING|NO", "TYPES|KIND|CHARACTER VARYING|NO", "TYPES|SUPERCLASS_BINARY_NAME|CHARACTER VARYING|YES", "TYPES|SOURCE_PATH|CHARACTER VARYING|NO", "TYPES|START_OFFSET|INTEGER|NO", "TYPES|END_OFFSET|INTEGER|NO", "TYPES|START_LINE|INTEGER|NO", "TYPES|END_LINE|INTEGER|NO", "TYPE_INTERFACES|TYPE_ID|BIGINT|NO", "TYPE_INTERFACES|ORDINAL|INTEGER|NO", "TYPE_INTERFACES|INTERFACE_BINARY_NAME|CHARACTER VARYING|NO", "FIELDS|ID|BIGINT|NO", "FIELDS|TYPE_ID|BIGINT|NO", "FIELDS|ORDINAL|INTEGER|NO", "FIELDS|NAME|CHARACTER VARYING|NO", "FIELDS|TYPE|CHARACTER VARYING|NO", "FIELDS|MODIFIERS|CHARACTER VARYING|NO", "FIELDS|START_OFFSET|INTEGER|NO", "FIELDS|END_OFFSET|INTEGER|NO", "FIELDS|START_LINE|INTEGER|NO", "FIELDS|END_LINE|INTEGER|NO", "METHODS|ID|BIGINT|NO", "METHODS|TYPE_ID|BIGINT|NO", "METHODS|ORDINAL|INTEGER|NO", "METHODS|NAME|CHARACTER VARYING|NO", "METHODS|DESCRIPTOR|CHARACTER VARYING|NO", "METHODS|RETURN_TYPE|CHARACTER VARYING|YES", "METHODS|MODIFIERS|CHARACTER VARYING|NO", "METHODS|CONSTRUCTOR|BOOLEAN|NO", "METHODS|START_OFFSET|INTEGER|NO", "METHODS|END_OFFSET|INTEGER|NO", "METHODS|START_LINE|INTEGER|NO", "METHODS|END_LINE|INTEGER|NO", "PARAMETERS|ID|BIGINT|NO", "PARAMETERS|METHOD_ID|BIGINT|NO", "PARAMETERS|ORDINAL|INTEGER|NO", "PARAMETERS|NAME|CHARACTER VARYING|NO", "PARAMETERS|TYPE|CHARACTER VARYING|NO", "PARAMETERS|VARARGS|BOOLEAN|NO", "PARAMETERS|START_OFFSET|INTEGER|NO", "PARAMETERS|END_OFFSET|INTEGER|NO", "PARAMETERS|START_LINE|INTEGER|NO", "PARAMETERS|END_LINE|INTEGER|NO");
    private static final Set<String> CONSTRAINT_SIGNATURES = Set.of("METADATA|PK_METADATA|PRIMARY KEY", "METADATA|CK_METADATA_SINGLETON|CHECK", "PACKAGES|PK_PACKAGES|PRIMARY KEY", "PACKAGES|CK_PACKAGES_SOURCE_IDENTITY|CHECK", "PACKAGES|UQ_PACKAGES_SOURCE_NAME|UNIQUE", "PACKAGES|UQ_PACKAGES_IDENTITY_REFERENCE|UNIQUE", "TYPES|PK_TYPES|PRIMARY KEY", "TYPES|CK_TYPES_SOURCE_IDENTITY|CHECK", "TYPES|CK_TYPES_KIND|CHECK", "TYPES|UQ_TYPES_BINARY_NAME|UNIQUE", "TYPES|FK_TYPES_PACKAGE_IDENTITY|FOREIGN KEY", "TYPE_INTERFACES|PK_TYPE_INTERFACES|PRIMARY KEY", "TYPE_INTERFACES|FK_TYPE_INTERFACES_TYPE|FOREIGN KEY", "FIELDS|PK_FIELDS|PRIMARY KEY", "FIELDS|UQ_FIELDS_TYPE_ORDINAL|UNIQUE", "FIELDS|FK_FIELDS_TYPE|FOREIGN KEY", "METHODS|PK_METHODS|PRIMARY KEY", "METHODS|UQ_METHODS_TYPE_ORDINAL|UNIQUE", "METHODS|FK_METHODS_TYPE|FOREIGN KEY", "PARAMETERS|PK_PARAMETERS|PRIMARY KEY", "PARAMETERS|UQ_PARAMETERS_METHOD_ORDINAL|UNIQUE", "PARAMETERS|FK_PARAMETERS_METHOD|FOREIGN KEY");
    private static final Set<String> KEY_COLUMN_SIGNATURES = Set.of("PK_METADATA|1|SINGLETON", "PK_PACKAGES|1|ID", "UQ_PACKAGES_SOURCE_NAME|1|SOURCE_NAMESPACE", "UQ_PACKAGES_SOURCE_NAME|2|FABRIC_API_VERSION_KEY", "UQ_PACKAGES_SOURCE_NAME|3|NAME", "UQ_PACKAGES_IDENTITY_REFERENCE|1|ID", "UQ_PACKAGES_IDENTITY_REFERENCE|2|SOURCE_NAMESPACE", "UQ_PACKAGES_IDENTITY_REFERENCE|3|FABRIC_API_VERSION_KEY", "PK_TYPES|1|ID", "UQ_TYPES_BINARY_NAME|1|BINARY_NAME", "FK_TYPES_PACKAGE_IDENTITY|1|PACKAGE_ID", "FK_TYPES_PACKAGE_IDENTITY|2|SOURCE_NAMESPACE", "FK_TYPES_PACKAGE_IDENTITY|3|FABRIC_API_VERSION_KEY", "PK_TYPE_INTERFACES|1|TYPE_ID", "PK_TYPE_INTERFACES|2|ORDINAL", "FK_TYPE_INTERFACES_TYPE|1|TYPE_ID", "PK_FIELDS|1|ID", "UQ_FIELDS_TYPE_ORDINAL|1|TYPE_ID", "UQ_FIELDS_TYPE_ORDINAL|2|ORDINAL", "FK_FIELDS_TYPE|1|TYPE_ID", "PK_METHODS|1|ID", "UQ_METHODS_TYPE_ORDINAL|1|TYPE_ID", "UQ_METHODS_TYPE_ORDINAL|2|ORDINAL", "FK_METHODS_TYPE|1|TYPE_ID", "PK_PARAMETERS|1|ID", "UQ_PARAMETERS_METHOD_ORDINAL|1|METHOD_ID", "UQ_PARAMETERS_METHOD_ORDINAL|2|ORDINAL", "FK_PARAMETERS_METHOD|1|METHOD_ID");
    private static final Map<String, String> CHECK_CLAUSES = Map.of("CK_METADATA_SINGLETON", "SINGLETON", "CK_PACKAGES_SOURCE_IDENTITY", "SOURCE_NAMESPACE='MINECRAFT'ANDFABRIC_API_VERSIONISNULLORSOURCE_NAMESPACE='FABRIC'ANDFABRIC_API_VERSIONISNOTNULLANDCHAR_LENGTHTRIMFABRIC_API_VERSION>0", "CK_TYPES_SOURCE_IDENTITY", "SOURCE_NAMESPACE='MINECRAFT'ANDFABRIC_API_VERSIONISNULLORSOURCE_NAMESPACE='FABRIC'ANDFABRIC_API_VERSIONISNOTNULLANDCHAR_LENGTHTRIMFABRIC_API_VERSION>0", "CK_TYPES_KIND", "KINDIN'CLASS','INTERFACE','ENUM','RECORD','ANNOTATION'");
    private static final Map<String, String> GENERATED_EXPRESSIONS = Map.of("PACKAGES|FABRIC_API_VERSION_KEY", "COALESCEFABRIC_API_VERSION,''", "TYPES|FABRIC_API_VERSION_KEY", "COALESCEFABRIC_API_VERSION,''");
    private static final Set<String> FOREIGN_KEY_SIGNATURES = Set.of("FK_TYPES_PACKAGE_IDENTITY|UQ_PACKAGES_IDENTITY_REFERENCE|NO ACTION|NO ACTION", "FK_TYPE_INTERFACES_TYPE|PK_TYPES|NO ACTION|CASCADE", "FK_FIELDS_TYPE|PK_TYPES|NO ACTION|CASCADE", "FK_METHODS_TYPE|PK_TYPES|NO ACTION|CASCADE", "FK_PARAMETERS_METHOD|PK_METHODS|NO ACTION|CASCADE");
    private static final Set<String> INDEX_COLUMN_SIGNATURES = Set.of("IDX_TYPE_BINARY_NAME|1|BINARY_NAME|false", "IDX_TYPES_PACKAGE_NAME|1|PACKAGE_ID|false", "IDX_TYPES_PACKAGE_NAME|2|SIMPLE_NAME|false", "IDX_FIELDS_TYPE_NAME|1|TYPE_ID|false", "IDX_FIELDS_TYPE_NAME|2|NAME|false", "IDX_FIELDS_TYPE_NAME|3|ORDINAL|false", "IDX_METHODS_TYPE_NAME|1|TYPE_ID|false", "IDX_METHODS_TYPE_NAME|2|NAME|false", "IDX_METHODS_TYPE_NAME|3|ORDINAL|false", "IDX_TYPE_INTERFACES_BINARY_NAME|1|INTERFACE_BINARY_NAME|false", "IDX_TYPE_INTERFACES_BINARY_NAME|2|TYPE_ID|false");

    private SymbolSchema() {
    }

    public static void create(Connection connection, MinecraftVersion minecraftVersion, Path sourceRoot, String remappedJarSha256, Instant builtAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(remappedJarSha256, "remappedJarSha256");
        Objects.requireNonNull(builtAt, "builtAt");
        try (Statement statement = connection.createStatement()) {
            execute(statement, "CREATE TABLE metadata (singleton BOOLEAN NOT NULL, schema_version INTEGER NOT NULL, minecraft_version VARCHAR NOT NULL, source_root VARCHAR NOT NULL, remapped_jar_sha256 VARCHAR NOT NULL, built_at TIMESTAMP WITH TIME ZONE NOT NULL, CONSTRAINT pk_metadata PRIMARY KEY(singleton), CONSTRAINT ck_metadata_singleton CHECK(singleton))");
            execute(statement, "CREATE TABLE packages (id BIGINT GENERATED BY DEFAULT AS IDENTITY, source_namespace VARCHAR NOT NULL, fabric_api_version VARCHAR, fabric_api_version_key VARCHAR GENERATED ALWAYS AS (COALESCE(fabric_api_version, '')) NOT NULL, name VARCHAR NOT NULL, CONSTRAINT pk_packages PRIMARY KEY(id), CONSTRAINT ck_packages_source_identity CHECK((source_namespace = 'minecraft' AND fabric_api_version IS NULL) OR (source_namespace = 'fabric' AND fabric_api_version IS NOT NULL AND LENGTH(TRIM(fabric_api_version)) > 0)), CONSTRAINT uq_packages_source_name UNIQUE(source_namespace, fabric_api_version_key, name), CONSTRAINT uq_packages_identity_reference UNIQUE(id, source_namespace, fabric_api_version_key))");
            execute(statement, "CREATE TABLE types (id BIGINT GENERATED BY DEFAULT AS IDENTITY, package_id BIGINT NOT NULL, source_namespace VARCHAR NOT NULL, fabric_api_version VARCHAR, fabric_api_version_key VARCHAR GENERATED ALWAYS AS (COALESCE(fabric_api_version, '')) NOT NULL, binary_name VARCHAR NOT NULL, simple_name VARCHAR NOT NULL, kind VARCHAR NOT NULL, superclass_binary_name VARCHAR, source_path VARCHAR NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, CONSTRAINT pk_types PRIMARY KEY(id), CONSTRAINT ck_types_source_identity CHECK((source_namespace = 'minecraft' AND fabric_api_version IS NULL) OR (source_namespace = 'fabric' AND fabric_api_version IS NOT NULL AND LENGTH(TRIM(fabric_api_version)) > 0)), CONSTRAINT ck_types_kind CHECK(kind IN ('class', 'interface', 'enum', 'record', 'annotation')), CONSTRAINT uq_types_binary_name UNIQUE(binary_name), CONSTRAINT fk_types_package_identity FOREIGN KEY(package_id, source_namespace, fabric_api_version_key) REFERENCES packages(id, source_namespace, fabric_api_version_key))");
            execute(statement, "CREATE TABLE type_interfaces (type_id BIGINT NOT NULL, ordinal INTEGER NOT NULL, interface_binary_name VARCHAR NOT NULL, CONSTRAINT pk_type_interfaces PRIMARY KEY(type_id, ordinal), CONSTRAINT fk_type_interfaces_type FOREIGN KEY(type_id) REFERENCES types(id) ON DELETE CASCADE)");
            execute(statement, "CREATE TABLE fields (id BIGINT GENERATED BY DEFAULT AS IDENTITY, type_id BIGINT NOT NULL, ordinal INTEGER NOT NULL, name VARCHAR NOT NULL, type VARCHAR NOT NULL, modifiers VARCHAR NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, CONSTRAINT pk_fields PRIMARY KEY(id), CONSTRAINT uq_fields_type_ordinal UNIQUE(type_id, ordinal), CONSTRAINT fk_fields_type FOREIGN KEY(type_id) REFERENCES types(id) ON DELETE CASCADE)");
            execute(statement, "CREATE TABLE methods (id BIGINT GENERATED BY DEFAULT AS IDENTITY, type_id BIGINT NOT NULL, ordinal INTEGER NOT NULL, name VARCHAR NOT NULL, descriptor VARCHAR NOT NULL, return_type VARCHAR, modifiers VARCHAR NOT NULL, constructor BOOLEAN NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, CONSTRAINT pk_methods PRIMARY KEY(id), CONSTRAINT uq_methods_type_ordinal UNIQUE(type_id, ordinal), CONSTRAINT fk_methods_type FOREIGN KEY(type_id) REFERENCES types(id) ON DELETE CASCADE)");
            execute(statement, "CREATE TABLE parameters (id BIGINT GENERATED BY DEFAULT AS IDENTITY, method_id BIGINT NOT NULL, ordinal INTEGER NOT NULL, name VARCHAR NOT NULL, type VARCHAR NOT NULL, varargs BOOLEAN NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, CONSTRAINT pk_parameters PRIMARY KEY(id), CONSTRAINT uq_parameters_method_ordinal UNIQUE(method_id, ordinal), CONSTRAINT fk_parameters_method FOREIGN KEY(method_id) REFERENCES methods(id) ON DELETE CASCADE)");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql("INSERT INTO metadata(singleton, schema_version, minecraft_version, source_root, remapped_jar_sha256, built_at) VALUES (TRUE, ?, ?, ?, ?, ?)"))) {
            statement.setInt(1, VERSION);
            statement.setString(2, minecraftVersion.value());
            statement.setString(3, sourceRoot.toString());
            statement.setString(4, remappedJarSha256);
            statement.setObject(5, builtAt);
            statement.executeUpdate();
        }
    }

    public static void createIndexes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            execute(statement, "CREATE INDEX idx_type_binary_name ON types(binary_name)");
            execute(statement, "CREATE INDEX idx_types_package_name ON types(package_id, simple_name)");
            execute(statement, "CREATE INDEX idx_fields_type_name ON fields(type_id, name, ordinal)");
            execute(statement, "CREATE INDEX idx_methods_type_name ON methods(type_id, name, ordinal)");
            execute(statement, "CREATE INDEX idx_type_interfaces_binary_name ON type_interfaces(interface_binary_name, type_id)");
        }
    }

    public static void validate(Connection connection) throws SQLException {
        validateTables(connection);
        validateColumns(connection);
        validateGeneratedExpressions(connection);
        validateConstraints(connection);
        validateKeyColumns(connection);
        validateCheckClauses(connection);
        validateForeignKeys(connection);
        validateSecondaryIndexes(connection);
        validateMetadata(connection);
        verifyNoOrphans(connection, "SELECT 1 FROM types t LEFT JOIN packages p ON p.id = t.package_id AND p.source_namespace = t.source_namespace AND p.fabric_api_version_key = t.fabric_api_version_key WHERE p.id IS NULL", "types.package identity");
        verifyNoOrphans(connection, "SELECT 1 FROM type_interfaces i LEFT JOIN types t ON t.id = i.type_id WHERE t.id IS NULL", "type_interfaces.type_id");
        verifyNoOrphans(connection, "SELECT 1 FROM fields f LEFT JOIN types t ON t.id = f.type_id WHERE t.id IS NULL", "fields.type_id");
        verifyNoOrphans(connection, "SELECT 1 FROM methods m LEFT JOIN types t ON t.id = m.type_id WHERE t.id IS NULL", "methods.type_id");
        verifyNoOrphans(connection, "SELECT 1 FROM parameters p LEFT JOIN methods m ON m.id = p.method_id WHERE m.id IS NULL", "parameters.method_id");
    }

    private static void validateTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'"))) {
            while (results.next()) {
                tables.add(results.getString(1));
            }
        }
        if (!tables.containsAll(TABLES)) {
            throw new SQLException("Missing required H2 symbol tables: expected " + TABLES + ", found " + tables);
        }
    }

    private static void validateColumns(Connection connection) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'PUBLIC'"))) {
            while (results.next()) {
                if (TABLES.contains(results.getString("table_name"))) {
                    actual.add(results.getString("table_name") + "|" + results.getString("column_name") + "|" + results.getString("data_type") + "|" + results.getString("is_nullable"));
                }
            }
        }
        requireExact("column contract", COLUMN_SIGNATURES, actual);
    }

    private static void validateGeneratedExpressions(Connection connection) throws SQLException {
        Map<String, String> actual = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT table_name, column_name, generation_expression FROM information_schema.columns WHERE table_schema = 'PUBLIC' AND is_generated = 'ALWAYS'"))) {
            while (results.next()) {
                if (TABLES.contains(results.getString("table_name"))) {
                    actual.put(results.getString("table_name") + "|" + results.getString("column_name"), normalizeExpression(results.getString("generation_expression")));
                }
            }
        }
        requireExact("generated-column contract", GENERATED_EXPRESSIONS, actual);
    }

    private static void validateConstraints(Connection connection) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT table_name, constraint_name, constraint_type FROM information_schema.table_constraints WHERE table_schema = 'PUBLIC'"))) {
            while (results.next()) {
                if (TABLES.contains(results.getString("table_name"))) {
                    actual.add(results.getString("table_name") + "|" + results.getString("constraint_name") + "|" + results.getString("constraint_type"));
                }
            }
        }
        requireExact("constraint contract", CONSTRAINT_SIGNATURES, actual);
    }

    private static void validateKeyColumns(Connection connection) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT constraint_name, ordinal_position, column_name FROM information_schema.key_column_usage WHERE constraint_schema = 'PUBLIC'"))) {
            while (results.next()) {
                if (isRequiredConstraint(results.getString("constraint_name"))) {
                    actual.add(results.getString("constraint_name") + "|" + results.getInt("ordinal_position") + "|" + results.getString("column_name"));
                }
            }
        }
        requireExact("constraint-column contract", KEY_COLUMN_SIGNATURES, actual);
    }

    private static void validateCheckClauses(Connection connection) throws SQLException {
        Map<String, String> actual = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT constraint_name, check_clause FROM information_schema.check_constraints WHERE constraint_schema = 'PUBLIC'"))) {
            while (results.next()) {
                if (CHECK_CLAUSES.containsKey(results.getString("constraint_name"))) {
                    actual.put(results.getString("constraint_name"), normalizeExpression(results.getString("check_clause")));
                }
            }
        }
        requireExact("check-constraint contract", CHECK_CLAUSES, actual);
    }

    private static void validateForeignKeys(Connection connection) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT constraint_name, unique_constraint_name, update_rule, delete_rule FROM information_schema.referential_constraints WHERE constraint_schema = 'PUBLIC'"))) {
            while (results.next()) {
                if (isRequiredConstraint(results.getString("constraint_name"))) {
                    actual.add(results.getString("constraint_name") + "|" + results.getString("unique_constraint_name") + "|" + results.getString("update_rule") + "|" + results.getString("delete_rule"));
                }
            }
        }
        requireExact("foreign-key contract", FOREIGN_KEY_SIGNATURES, actual);
    }

    private static void validateSecondaryIndexes(Connection connection) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT index_name, ordinal_position, column_name, is_unique FROM information_schema.index_columns WHERE index_schema = 'PUBLIC' AND index_name LIKE 'IDX_%'"))) {
            while (results.next()) {
                actual.add(results.getString("index_name") + "|" + results.getInt("ordinal_position") + "|" + results.getString("column_name") + "|" + results.getBoolean("is_unique"));
            }
        }
        requireExact("secondary-index contract", INDEX_COLUMN_SIGNATURES, actual);
    }

    private static void validateMetadata(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql("SELECT singleton, schema_version, minecraft_version, source_root, remapped_jar_sha256, built_at FROM metadata"))) {
            if (!results.next() || !results.getBoolean("singleton") || results.getInt("schema_version") != VERSION || results.getString("minecraft_version").isBlank() || results.getString("source_root").isBlank() || !results.getString("remapped_jar_sha256").matches("[0-9a-fA-F]{64}") || results.getObject("built_at") == null || results.next()) {
                throw new SQLException("Expected exactly one complete metadata row with schema version " + VERSION);
            }
        }
    }

    private static boolean isRequiredConstraint(String constraintName) {
        return CONSTRAINT_SIGNATURES.stream().anyMatch(signature -> signature.contains("|" + constraintName + "|"));
    }

    private static void verifyNoOrphans(Connection connection, String query, String relationship) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(query)) {
            if (results.next()) {
                throw new SQLException("H2 orphan validation failed for " + relationship);
            }
        }
    }

    private static String normalizeExpression(String expression) {
        return expression.toUpperCase(Locale.ROOT).replaceAll("[\\s\"()]", "");
    }

    private static void requireExact(String contract, Object expected, Object actual) throws SQLException {
        if (!expected.equals(actual)) {
            throw new SQLException("Invalid H2 symbol " + contract + ": expected " + expected + ", found " + actual);
        }
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        statement.execute(sql);
    }

    private static String sql(String statement) {
        return statement;
    }
}