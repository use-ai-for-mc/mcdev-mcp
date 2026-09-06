package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexRequest;

import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

import javax.lang.model.element.Modifier;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
record SymbolIndexSnapshot(SymbolIndexMetadata metadata, List<IndexedPackageSnapshot> packages, List<IndexedTypeSnapshot> types, List<IndexedInterfaceSnapshot> interfaces, List<IndexedFieldSnapshot> fields, List<IndexedMethodSnapshot> methods, List<IndexedParameterSnapshot> parameters) {
    SymbolIndexSnapshot {
        packages = List.copyOf(packages);
        types = List.copyOf(types);
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        parameters = List.copyOf(parameters);
    }

    static SymbolIndexSnapshot expected(IndexRequest request, String remappedJarSha256, Instant builtAt, List<IndexedPackage> packages, List<ParsedType> parsedTypes) {
        SymbolIndexMetadata metadata = new SymbolIndexMetadata(true, dev.mcdevmcp.storage.h2.SymbolSchema.VERSION, request.minecraftVersion(), request.sourceRoots().getFirst().path(), remappedJarSha256, builtAt);
        List<IndexedPackageSnapshot> expectedPackages = packages.stream().map(indexedPackage -> new IndexedPackageSnapshot(indexedPackage.id(), indexedPackage.namespace(), indexedPackage.fabricApiVersion(), indexedPackage.fabricApiVersion().map(FabricApiVersion::value).orElse(""), indexedPackage.name())).toList();
        List<IndexedTypeSnapshot> expectedTypes = new ArrayList<>();
        List<IndexedInterfaceSnapshot> expectedInterfaces = new ArrayList<>();
        List<IndexedFieldSnapshot> expectedFields = new ArrayList<>();
        List<IndexedMethodSnapshot> expectedMethods = new ArrayList<>();
        List<IndexedParameterSnapshot> expectedParameters = new ArrayList<>();
        long fieldId = 1;
        long methodId = 1;
        long parameterId = 1;
        for (int typeIndex = 0; typeIndex < parsedTypes.size(); typeIndex++) {
            ParsedType type = parsedTypes.get(typeIndex);
            long typeId = typeIndex + 1L;
            IndexedPackage indexedPackage = packages.stream().filter(candidate -> new PackageIdentity(candidate.namespace(), candidate.fabricApiVersion(), candidate.name()).equals(new PackageIdentity(type))).findFirst().orElseThrow();
            expectedTypes.add(new IndexedTypeSnapshot(typeId, indexedPackage.id(), type.sourceRoot().namespace(), type.sourceRoot().fabricApiVersion(), type.sourceRoot().fabricApiVersion().map(FabricApiVersion::value).orElse(""), type.binaryName(), type.simpleName(), type.kind(), type.superclass(), new PortablePath(type.sourcePath()), type.range()));
            for (int interfaceIndex = 0; interfaceIndex < type.interfaces().size(); interfaceIndex++) {
                expectedInterfaces.add(new IndexedInterfaceSnapshot(typeId, interfaceIndex, type.interfaces().get(interfaceIndex)));
            }
            for (ParsedField field : type.fields()) {
                expectedFields.add(new IndexedFieldSnapshot(fieldId++, typeId, field.ordinal(), field.name(), field.type(), modifiers(field.modifiers()), field.range()));
            }
            for (ParsedMethod method : type.methods()) {
                long currentMethodId = methodId++;
                expectedMethods.add(new IndexedMethodSnapshot(currentMethodId, typeId, method.ordinal(), method.name(), method.descriptor(), method.returnType(), modifiers(method.modifiers()), method.constructor(), method.range()));
                for (ParsedParameter parameter : method.parameters()) {
                    expectedParameters.add(new IndexedParameterSnapshot(parameterId++, currentMethodId, parameter.ordinal(), parameter.name(), parameter.type(), parameter.varargs(), parameter.range()));
                }
            }
        }
        return new SymbolIndexSnapshot(metadata, expectedPackages, expectedTypes, expectedInterfaces, expectedFields, expectedMethods, expectedParameters);
    }

    private static SymbolIndexSnapshot read(Connection connection) throws SQLException {
        SymbolIndexMetadata metadata;
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT singleton, schema_version, minecraft_version, source_root, remapped_jar_sha256, built_at FROM metadata ORDER BY singleton")) {
            if (!results.next()) {
                throw new SQLException("Missing symbol metadata row");
            }
            metadata = new SymbolIndexMetadata(results.getBoolean(1), results.getInt(2), new MinecraftVersion(results.getString(3)), Path.of(results.getString(4)), results.getString(5), results.getObject(6, OffsetDateTime.class).toInstant());
            if (results.next()) {
                throw new SQLException("Unexpected additional symbol metadata row");
            }
        }
        List<IndexedPackageSnapshot> packages = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT id, source_namespace, fabric_api_version, fabric_api_version_key, name FROM packages ORDER BY id")) {
            while (results.next()) {
                packages.add(new IndexedPackageSnapshot(results.getLong(1), SourceNamespace.fromWireName(results.getString(2)), optionalVersion(results.getString(3)), results.getString(4), results.getString(5)));
            }
        }
        List<IndexedTypeSnapshot> types = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT id, package_id, source_namespace, fabric_api_version, fabric_api_version_key, binary_name, simple_name, kind, superclass_binary_name, source_path, start_offset, end_offset, start_line, end_line FROM types ORDER BY id")) {
            while (results.next()) {
                types.add(new IndexedTypeSnapshot(results.getLong(1), results.getLong(2), SourceNamespace.fromWireName(results.getString(3)), optionalVersion(results.getString(4)), results.getString(5), results.getString(6), results.getString(7), ElementKindCodec.fromWireName(results.getString(8)), optionalClass(results.getString(9)), new PortablePath(Path.of(results.getString(10))), range(results, 11)));
            }
        }
        List<IndexedInterfaceSnapshot> interfaces = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT type_id, ordinal, interface_binary_name FROM type_interfaces ORDER BY type_id, ordinal")) {
            while (results.next()) {
                interfaces.add(new IndexedInterfaceSnapshot(results.getLong(1), results.getInt(2), ClassDesc.of(results.getString(3))));
            }
        }
        List<IndexedFieldSnapshot> fields = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT id, type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line FROM fields ORDER BY id")) {
            while (results.next()) {
                fields.add(new IndexedFieldSnapshot(results.getLong(1), results.getLong(2), results.getInt(3), results.getString(4), results.getString(5), modifiers(results.getString(6)), range(results, 7)));
            }
        }
        List<IndexedMethodSnapshot> methods = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT id, type_id, ordinal, name, descriptor, return_type, modifiers, constructor, start_offset, end_offset, start_line, end_line FROM methods ORDER BY id")) {
            while (results.next()) {
                methods.add(new IndexedMethodSnapshot(results.getLong(1), results.getLong(2), results.getInt(3), results.getString(4), MethodTypeDesc.ofDescriptor(results.getString(5)), Optional.ofNullable(results.getString(6)), modifiers(results.getString(7)), results.getBoolean(8), range(results, 9)));
            }
        }
        List<IndexedParameterSnapshot> parameters = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT id, method_id, ordinal, name, type, varargs, start_offset, end_offset, start_line, end_line FROM parameters ORDER BY id")) {
            while (results.next()) {
                parameters.add(new IndexedParameterSnapshot(results.getLong(1), results.getLong(2), results.getInt(3), results.getString(4), results.getString(5), results.getBoolean(6), range(results, 7)));
            }
        }
        return new SymbolIndexSnapshot(metadata, packages, types, interfaces, fields, methods, parameters);
    }

    private static Optional<FabricApiVersion> optionalVersion(String value) {
        return Optional.ofNullable(value).map(FabricApiVersion::new);
    }

    private static Optional<ClassDesc> optionalClass(String value) {
        return Optional.ofNullable(value).map(ClassDesc::of);
    }

    private static List<Modifier> modifiers(java.util.Set<Modifier> modifiers) {
        return Arrays.stream(Modifier.values()).filter(modifiers::contains).toList();
    }

    private static List<Modifier> modifiers(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(",", -1)).map(name -> Modifier.valueOf(name.toUpperCase(Locale.ROOT))).toList();
    }

    private static SourceRange range(ResultSet results, int firstColumn) throws SQLException {
        return new SourceRange(results.getInt(firstColumn), results.getInt(firstColumn + 1), results.getInt(firstColumn + 2), results.getInt(firstColumn + 3));
    }

    void validate(Connection connection) throws SQLException {
        SymbolIndexSnapshot actual = read(connection);
        if (!equals(actual)) {
            throw new SQLException("Persisted symbol projection does not exactly match the immutable build projection: expected " + this + ", found " + actual);
        }
    }
}