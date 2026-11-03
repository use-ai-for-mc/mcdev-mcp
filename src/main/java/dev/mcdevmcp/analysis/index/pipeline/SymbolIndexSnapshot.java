package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassDescriptors;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.PublishedSourceRoot;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

import javax.lang.model.element.Modifier;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

value record SymbolIndexSnapshot(SymbolIndexMetadata metadata, List<IndexedPackageSnapshot> packages, List<IndexedTypeSnapshot> types, List<IndexedInterfaceSnapshot> interfaces, List<IndexedFieldSnapshot> fields, List<IndexedMethodSnapshot> methods, List<IndexedParameterSnapshot> parameters) {
    SymbolIndexSnapshot {
        packages = List.copyOf(packages);
        types = List.copyOf(types);
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        parameters = List.copyOf(parameters);
    }

    static SymbolIndexSnapshot expected(IndexRequest request, String remappedJarSha256, Instant builtAt, List<IndexedPackage> packages, List<ParsedType> parsedTypes, PublishedSourceRoot publishedSourceRoot) {
        SymbolIndexMetadata metadata = new SymbolIndexMetadata(true, SymbolIndexData.FORMAT, request.minecraftVersion(), publishedSourceRoot.path(), remappedJarSha256, builtAt);
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

    private static SymbolIndexSnapshot from(SymbolIndexData data) {
        SymbolIndexData.Header header = data.header();
        SymbolIndexMetadata metadata = new SymbolIndexMetadata(true, header.format(), new MinecraftVersion(header.minecraftVersion()), Path.of(header.sourceRoot()), header.remappedJarSha256(), Instant.parse(header.builtAt()));
        List<IndexedPackageSnapshot> packages = data.packages().stream().map(row -> new IndexedPackageSnapshot(row.id(), SourceNamespace.fromWireName(row.namespace()), optionalVersion(row.fabricApiVersion()), versionKey(row.fabricApiVersion()), row.name())).toList();
        List<IndexedTypeSnapshot> types = new ArrayList<>();
        List<IndexedInterfaceSnapshot> interfaces = new ArrayList<>();
        for (SymbolIndexData.TypeRow row : data.types()) {
            types.add(new IndexedTypeSnapshot(row.id(), row.packageId(), SourceNamespace.fromWireName(row.namespace()), optionalVersion(row.fabricApiVersion()), versionKey(row.fabricApiVersion()), row.binaryName(), row.simpleName(), ElementKindCodec.fromWireName(row.kind()), optionalClass(row.superclass()), new PortablePath(Path.of(row.sourcePath())), new SourceRange(row.startOffset(), row.endOffset(), row.startLine(), row.endLine())));
            for (int ordinal = 0; ordinal < row.interfaces().size(); ordinal++) {
                interfaces.add(new IndexedInterfaceSnapshot(row.id(), ordinal, ClassDesc.of(row.interfaces().get(ordinal))));
            }
        }
        List<IndexedFieldSnapshot> fields = data.fields().stream().map(row -> new IndexedFieldSnapshot(row.id(), row.typeId(), row.ordinal(), row.name(), row.type(), modifiers(row.modifiers()), new SourceRange(row.startOffset(), row.endOffset(), row.startLine(), row.endLine()))).toList();
        List<IndexedMethodSnapshot> methods = data.methods().stream().map(row -> new IndexedMethodSnapshot(row.id(), row.typeId(), row.ordinal(), row.name(), MethodTypeDesc.ofDescriptor(row.descriptor()), Optional.ofNullable(row.returnType()), modifiers(row.modifiers()), row.constructor(), new SourceRange(row.startOffset(), row.endOffset(), row.startLine(), row.endLine()))).toList();
        List<IndexedParameterSnapshot> parameters = data.parameters().stream().map(row -> new IndexedParameterSnapshot(row.id(), row.methodId(), row.ordinal(), row.name(), row.type(), row.varargs(), new SourceRange(row.startOffset(), row.endOffset(), row.startLine(), row.endLine()))).toList();
        return new SymbolIndexSnapshot(metadata, packages, types, interfaces, fields, methods, parameters);
    }

    private static Optional<FabricApiVersion> optionalVersion(String value) {
        return Optional.ofNullable(value).map(FabricApiVersion::new);
    }

    private static String versionKey(String value) {
        return value == null ? "" : value;
    }

    private static Optional<ClassDesc> optionalClass(String value) {
        return Optional.ofNullable(value).map(ClassDesc::of);
    }

    private static List<Modifier> modifiers(Set<Modifier> modifiers) {
        return Arrays.stream(Modifier.values()).filter(modifiers::contains).toList();
    }

    private static List<Modifier> modifiers(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(",", -1)).map(name -> Modifier.valueOf(name.toUpperCase(Locale.ROOT))).toList();
    }

    private static String wireModifiers(List<Modifier> modifiers) {
        return modifiers.stream().map(modifier -> modifier.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(","));
    }

    SymbolIndexData toData() {
        Map<Long, List<String>> interfaceNames = new HashMap<>();
        for (IndexedInterfaceSnapshot implemented : interfaces.stream().sorted(Comparator.comparingLong(IndexedInterfaceSnapshot::typeId).thenComparingInt(IndexedInterfaceSnapshot::ordinal)).toList()) {
            interfaceNames.computeIfAbsent(implemented.typeId(), _ -> new ArrayList<>()).add(ClassDescriptors.binaryName(implemented.interfaceType()));
        }
        List<SymbolIndexData.PackageRow> packageRows = packages.stream().map(value -> new SymbolIndexData.PackageRow(value.id(), value.namespace().wireName(), value.fabricApiVersion().map(FabricApiVersion::value).orElse(null), value.name())).toList();
        List<SymbolIndexData.TypeRow> typeRows = types.stream().map(value -> new SymbolIndexData.TypeRow(value.id(), value.packageId(), value.namespace().wireName(), value.fabricApiVersion().map(FabricApiVersion::value).orElse(null), value.binaryName(), value.simpleName(), ElementKindCodec.wireName(value.kind()), value.superclass().map(ClassDescriptors::binaryName).orElse(null), interfaceNames.getOrDefault(value.id(), List.of()), value.sourcePath().value(), value.range().startOffset(), value.range().endOffset(), value.range().startLine(), value.range().endLine())).toList();
        List<SymbolIndexData.FieldRow> fieldRows = fields.stream().map(value -> new SymbolIndexData.FieldRow(value.id(), value.typeId(), value.ordinal(), value.name(), value.type(), wireModifiers(value.modifiers()), value.range().startOffset(), value.range().endOffset(), value.range().startLine(), value.range().endLine())).toList();
        List<SymbolIndexData.MethodRow> methodRows = methods.stream().map(value -> new SymbolIndexData.MethodRow(value.id(), value.typeId(), value.ordinal(), value.name(), value.descriptor().descriptorString(), value.returnType().orElse(null), wireModifiers(value.modifiers()), value.constructor(), value.range().startOffset(), value.range().endOffset(), value.range().startLine(), value.range().endLine())).toList();
        List<SymbolIndexData.ParameterRow> parameterRows = parameters.stream().map(value -> new SymbolIndexData.ParameterRow(value.id(), value.methodId(), value.ordinal(), value.name(), value.type(), value.varargs(), value.range().startOffset(), value.range().endOffset(), value.range().startLine(), value.range().endLine())).toList();
        return SymbolIndexData.of(metadata.minecraftVersion().value(), metadata.sourceRoot().toString(), metadata.remappedJarSha256(), metadata.builtAt().toString(), packageRows, typeRows, fieldRows, methodRows, parameterRows);
    }

    void validate(Path file) throws IOException {
        SymbolIndexSnapshot actual = from(SymbolIndexFiles.read(file));
        if (!equals(actual)) {
            throw new IOException("Persisted symbol projection does not exactly match the immutable build projection: expected " + this + ", found " + actual);
        }
    }
}
