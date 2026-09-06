package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassDescriptors;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.storage.h2.AtomicH2Database;
import dev.mcdevmcp.storage.h2.DatabaseValidator;
import dev.mcdevmcp.storage.h2.SymbolSchema;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FabricApiVersion;

import javax.lang.model.element.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

final class SymbolIndexWriter {
    private final AtomicH2Database databases;
    private final DatabaseValidator beforeValidation;

    SymbolIndexWriter() {
        this(new AtomicH2Database(), _ -> {
        });
    }

    SymbolIndexWriter(AtomicH2Database databases, DatabaseValidator beforeValidation) {
        this.databases = Objects.requireNonNull(databases, "databases");
        this.beforeValidation = Objects.requireNonNull(beforeValidation, "beforeValidation");
    }

    private static List<IndexedPackage> packages(List<ParsedType> types) {
        SortedSet<PackageIdentity> identities = new TreeSet<>();
        types.stream().map(PackageIdentity::new).forEach(identities::add);
        List<IndexedPackage> packages = new ArrayList<>(identities.size());
        long id = 1;
        for (PackageIdentity identity : identities) {
            packages.add(new IndexedPackage(id++, identity.namespace(), identity.fabricApiVersion(), identity.packageName()));
        }
        return List.copyOf(packages);
    }

    private static IndexCounts counts(List<IndexedPackage> packages, List<ParsedType> types) {
        int fields = types.stream().mapToInt(type -> type.fields().size()).sum();
        int methods = types.stream().mapToInt(type -> type.methods().size()).sum();
        int parameters = types.stream().flatMap(type -> type.methods().stream()).mapToInt(method -> method.parameters().size()).sum();
        return new IndexCounts(packages.size(), types.size(), fields, methods, parameters);
    }

    private static void insertPackages(Connection connection, List<IndexedPackage> packages) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql("INSERT INTO packages(id, source_namespace, fabric_api_version, name) VALUES (?, ?, ?, ?)"))) {
            for (IndexedPackage indexedPackage : packages) {
                statement.setLong(1, indexedPackage.id());
                statement.setString(2, indexedPackage.namespace().wireName());
                setOptional(statement, 3, indexedPackage.fabricApiVersion().map(FabricApiVersion::value).orElse(null));
                statement.setString(4, indexedPackage.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertTypesAndMembers(Connection connection, List<IndexedPackage> packages, List<ParsedType> types, IndexRequest request) throws Exception {
        Map<PackageIdentity, Long> packageIds = new HashMap<>();
        packages.forEach(indexedPackage -> packageIds.put(new PackageIdentity(indexedPackage.namespace(), indexedPackage.fabricApiVersion(), indexedPackage.name()), indexedPackage.id()));
        long fieldId = 1;
        long methodId = 1;
        long parameterId = 1;
        try (PreparedStatement typeStatement = connection.prepareStatement(sql("INSERT INTO types(id, package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, superclass_binary_name, source_path, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
             PreparedStatement interfaceStatement = connection.prepareStatement(sql("INSERT INTO type_interfaces(type_id, ordinal, interface_binary_name) VALUES (?, ?, ?)"));
             PreparedStatement fieldStatement = connection.prepareStatement(sql("INSERT INTO fields(id, type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
             PreparedStatement methodStatement = connection.prepareStatement(sql("INSERT INTO methods(id, type_id, ordinal, name, descriptor, return_type, modifiers, constructor, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
             PreparedStatement parameterStatement = connection.prepareStatement(sql("INSERT INTO parameters(id, method_id, ordinal, name, type, varargs, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))) {
            for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
                request.cancellation().throwIfCancelled();
                ParsedType type = types.get(typeIndex);
                long typeId = typeIndex + 1L;
                Long packageId = packageIds.get(new PackageIdentity(type));
                if (packageId == null) {
                    throw new SQLException("No deterministic package ID for " + type.binaryName());
                }
                typeStatement.setLong(1, typeId);
                typeStatement.setLong(2, packageId);
                typeStatement.setString(3, type.sourceRoot().namespace().wireName());
                setOptional(typeStatement, 4, type.sourceRoot().fabricApiVersion().map(FabricApiVersion::value).orElse(null));
                typeStatement.setString(5, type.binaryName());
                typeStatement.setString(6, type.simpleName());
                typeStatement.setString(7, ElementKindCodec.wireName(type.kind()));
                setOptional(typeStatement, 8, type.superclass().map(ClassDescriptors::binaryName).orElse(null));
                typeStatement.setString(9, new PortablePath(type.sourcePath()).value());
                setRange(typeStatement, 10, type.range());
                typeStatement.addBatch();
                for (int interfaceIndex = 0; interfaceIndex < type.interfaces().size(); interfaceIndex++) {
                    interfaceStatement.setLong(1, typeId);
                    interfaceStatement.setInt(2, interfaceIndex);
                    interfaceStatement.setString(3, ClassDescriptors.binaryName(type.interfaces().get(interfaceIndex)));
                    interfaceStatement.addBatch();
                }
                for (ParsedField field : type.fields()) {
                    fieldStatement.setLong(1, fieldId++);
                    fieldStatement.setLong(2, typeId);
                    fieldStatement.setInt(3, field.ordinal());
                    fieldStatement.setString(4, field.name());
                    fieldStatement.setString(5, field.type());
                    fieldStatement.setString(6, modifiers(field.modifiers()));
                    setRange(fieldStatement, 7, field.range());
                    fieldStatement.addBatch();
                }
                for (ParsedMethod method : type.methods()) {
                    long currentMethodId = methodId++;
                    methodStatement.setLong(1, currentMethodId);
                    methodStatement.setLong(2, typeId);
                    methodStatement.setInt(3, method.ordinal());
                    methodStatement.setString(4, method.name());
                    methodStatement.setString(5, method.descriptor().descriptorString());
                    setOptional(methodStatement, 6, method.returnType().orElse(null));
                    methodStatement.setString(7, modifiers(method.modifiers()));
                    methodStatement.setBoolean(8, method.constructor());
                    setRange(methodStatement, 9, method.range());
                    methodStatement.addBatch();
                    for (ParsedParameter parameter : method.parameters()) {
                        parameterStatement.setLong(1, parameterId++);
                        parameterStatement.setLong(2, currentMethodId);
                        parameterStatement.setInt(3, parameter.ordinal());
                        parameterStatement.setString(4, parameter.name());
                        parameterStatement.setString(5, parameter.type());
                        parameterStatement.setBoolean(6, parameter.varargs());
                        setRange(parameterStatement, 7, parameter.range());
                        parameterStatement.addBatch();
                    }
                }
            }
            typeStatement.executeBatch();
            interfaceStatement.executeBatch();
            fieldStatement.executeBatch();
            methodStatement.executeBatch();
            parameterStatement.executeBatch();
        }
    }

    private static void setRange(PreparedStatement statement, int firstColumn, SourceRange range) throws SQLException {
        statement.setInt(firstColumn, range.startOffset());
        statement.setInt(firstColumn + 1, range.endOffset());
        statement.setInt(firstColumn + 2, range.startLine());
        statement.setInt(firstColumn + 3, range.endLine());
    }

    private static void setOptional(PreparedStatement statement, int column, String value) throws SQLException {
        if (value != null) {
            statement.setString(column, value);
        }
        else {
            statement.setNull(column, Types.VARCHAR);
        }
    }

    private static String modifiers(Set<Modifier> modifiers) {
        return Arrays.stream(Modifier.values()).filter(modifiers::contains).map(modifier -> modifier.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining(","));
    }

    private static String sql(String statement) {
        return statement;
    }

    IndexCounts write(IndexRequest request, ParsedIndex index, String remappedJarSha256, Instant builtAt) throws Exception {
        List<IndexedPackage> packages = packages(index.types());
        IndexCounts counts = counts(packages, index.types());
        Instant persistedBuiltAt = builtAt.truncatedTo(ChronoUnit.MICROS);
        SymbolIndexSnapshot expected = SymbolIndexSnapshot.expected(request, remappedJarSha256, persistedBuiltAt, packages, index.types());
        return databases.rebuild(request.outputDatabase(), AtomicH2Database.WRITE_LOCK_TIMEOUT, connection -> {
            request.cancellation().throwIfCancelled();
            SymbolSchema.create(connection, request.minecraftVersion(), request.sourceRoots().getFirst().path(), remappedJarSha256, persistedBuiltAt);
            insertPackages(connection, packages);
            insertTypesAndMembers(connection, packages, index.types(), request);
            SymbolSchema.createIndexes(connection);
            beforeValidation.validate(connection);
            return counts;
        }, SymbolSchema::validate, connection -> {
            SymbolSchema.validate(connection);
            expected.validate(connection);
        });
    }
}