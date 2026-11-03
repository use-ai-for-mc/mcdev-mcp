package dev.mcdevmcp.storage.symbols;

import java.util.List;
import java.util.Objects;

/**
 * The complete persisted symbol index: one header and five row sections.
 */
public value record SymbolIndexData(Header header, List<PackageRow> packages, List<TypeRow> types, List<FieldRow> fields, List<MethodRow> methods, List<ParameterRow> parameters) {
    /**
     * Format 2 holds member types as well as top-level ones; a format 1 index has to be rebuilt.
     */
    public static final int FORMAT = 2;

    public SymbolIndexData {
        Objects.requireNonNull(header, "header");
        packages = List.copyOf(packages);
        types = List.copyOf(types);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        parameters = List.copyOf(parameters);
        if (header.packages() != packages.size() || header.types() != types.size() || header.fields() != fields.size() || header.methods() != methods.size() || header.parameters() != parameters.size()) {
            throw new IllegalArgumentException("Symbol index header counts differ from its rows");
        }
    }

    public static SymbolIndexData of(String minecraftVersion, String sourceRoot, String remappedJarSha256, String builtAt, List<PackageRow> packages, List<TypeRow> types, List<FieldRow> fields, List<MethodRow> methods, List<ParameterRow> parameters) {
        return new SymbolIndexData(new Header(FORMAT, minecraftVersion, sourceRoot, remappedJarSha256, builtAt, packages.size(), types.size(), fields.size(), methods.size(), parameters.size()), packages, types, fields, methods, parameters);
    }

    public value record Header(int format, String minecraftVersion, String sourceRoot, String remappedJarSha256, String builtAt, int packages, int types, int fields, int methods, int parameters) {
        public Header {
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            Objects.requireNonNull(remappedJarSha256, "remappedJarSha256");
            Objects.requireNonNull(builtAt, "builtAt");
            if (packages < 0 || types < 0 || fields < 0 || methods < 0 || parameters < 0) {
                throw new IllegalArgumentException("Symbol index counts must not be negative");
            }
        }
    }

    public value record PackageRow(long id, String namespace, String fabricApiVersion, String name) {
        public PackageRow {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(name, "name");
        }
    }

    public value record TypeRow(long id, long packageId, String namespace, String fabricApiVersion, String binaryName, String simpleName, String kind, String superclass, List<String> interfaces, String sourcePath, int startOffset, int endOffset, int startLine, int endLine) {
        public TypeRow {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(binaryName, "binaryName");
            Objects.requireNonNull(simpleName, "simpleName");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sourcePath, "sourcePath");
            interfaces = List.copyOf(Objects.requireNonNull(interfaces, "interfaces"));
        }
    }

    public value record FieldRow(long id, long typeId, int ordinal, String name, String type, String modifiers, int startOffset, int endOffset, int startLine, int endLine) {
        public FieldRow {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(modifiers, "modifiers");
        }
    }

    public value record MethodRow(long id, long typeId, int ordinal, String name, String descriptor, String returnType, String modifiers, boolean constructor, int startOffset, int endOffset, int startLine, int endLine) {
        public MethodRow {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(modifiers, "modifiers");
        }
    }

    public value record ParameterRow(long id, long methodId, int ordinal, String name, String type, boolean varargs, int startOffset, int endOffset, int startLine, int endLine) {
        public ParameterRow {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }
}
