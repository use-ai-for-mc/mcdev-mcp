package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.model.*;

import javax.lang.model.element.Modifier;
import java.nio.file.Path;
import java.util.*;

/**
 * An immutable in-memory symbol index answering every query in Node's namespace/package/type/member order.
 */
public value class SymbolIndex {
    private final List<SymbolIndexData.PackageRow> packages;
    private final List<ClassSymbol> types;
    private final List<String> lowerPackageNames;
    private final List<String> lowerSimpleNames;
    private final Map<String, ClassSymbol> byBinaryName;
    private final Map<String, ClassSymbol> bySourceName;
    private final Map<String, List<ClassSymbol>> memberTypes;
    private final Map<Long, List<FieldSymbol>> fields;
    private final Map<Long, List<MethodSymbol>> methods;
    private final Map<Long, List<ParameterSymbol>> parameters;
    private final Map<String, List<ClassSymbol>> subclasses;
    private final Map<String, List<ClassSymbol>> implementors;

    private SymbolIndex(List<SymbolIndexData.PackageRow> packages, List<ClassSymbol> types, List<String> lowerPackageNames, List<String> lowerSimpleNames, Map<String, ClassSymbol> byBinaryName, Map<String, ClassSymbol> bySourceName, Map<String, List<ClassSymbol>> memberTypes, Map<Long, List<FieldSymbol>> fields, Map<Long, List<MethodSymbol>> methods, Map<Long, List<ParameterSymbol>> parameters, Map<String, List<ClassSymbol>> subclasses, Map<String, List<ClassSymbol>> implementors) {
        this.packages = packages;
        this.types = types;
        this.lowerPackageNames = lowerPackageNames;
        this.lowerSimpleNames = lowerSimpleNames;
        this.byBinaryName = byBinaryName;
        this.bySourceName = bySourceName;
        this.memberTypes = memberTypes;
        this.fields = fields;
        this.methods = methods;
        this.parameters = parameters;
        this.subclasses = subclasses;
        this.implementors = implementors;
    }

    public static SymbolIndex of(SymbolIndexData data) {
        Objects.requireNonNull(data, "data");
        List<SymbolIndexData.PackageRow> orderedPackages = data.packages().stream().sorted(Comparator.<SymbolIndexData.PackageRow>comparingInt(row -> rank(row.namespace())).thenComparingLong(SymbolIndexData.PackageRow::id)).toList();
        Map<Long, String> packageNames = new HashMap<>();
        for (SymbolIndexData.PackageRow row : data.packages()) {
            packageNames.put(row.id(), row.name());
        }
        List<SymbolIndexData.TypeRow> orderedRows = data.types().stream().sorted(Comparator.<SymbolIndexData.TypeRow>comparingInt(row -> rank(row.namespace())).thenComparingLong(SymbolIndexData.TypeRow::packageId).thenComparingLong(SymbolIndexData.TypeRow::id)).toList();
        List<ClassSymbol> types = new ArrayList<>(orderedRows.size());
        List<String> lowerPackageNames = new ArrayList<>(orderedRows.size());
        List<String> lowerSimpleNames = new ArrayList<>(orderedRows.size());
        Map<Long, ClassSymbol> byId = new HashMap<>();
        Map<String, List<ClassSymbol>> subclasses = new HashMap<>();
        Map<String, List<ClassSymbol>> implementors = new HashMap<>();
        for (SymbolIndexData.TypeRow row : orderedRows) {
            String packageName = packageNames.get(row.packageId());
            if (packageName == null) {
                throw new IllegalArgumentException("Indexed type refers to an unknown package: " + row.binaryName());
            }
            ClassSymbol symbol = new ClassSymbol(row.id(), SourceNamespace.fromWireName(row.namespace()), Optional.ofNullable(row.fabricApiVersion()).map(FabricApiVersion::new), row.binaryName(), packageName, row.simpleName(), ElementKindCodec.fromWireName(row.kind()), Optional.ofNullable(row.superclass()), row.interfaces(), Path.of(row.sourcePath()), row.startOffset(), row.endOffset(), row.startLine(), row.endLine());
            types.add(symbol);
            lowerPackageNames.add(packageName.toLowerCase(Locale.ROOT));
            lowerSimpleNames.add(row.simpleName().toLowerCase(Locale.ROOT));
            byId.put(row.id(), symbol);
            if (row.superclass() != null) {
                subclasses.computeIfAbsent(row.superclass(), _ -> new ArrayList<>()).add(symbol);
            }
            for (String implemented : row.interfaces()) {
                implementors.computeIfAbsent(implemented, _ -> new ArrayList<>()).add(symbol);
            }
        }
        Map<String, ClassSymbol> byBinaryName = new HashMap<>();
        for (SymbolIndexData.TypeRow row : data.types().stream().sorted(Comparator.comparingLong(SymbolIndexData.TypeRow::id)).toList()) {
            byBinaryName.putIfAbsent(row.binaryName(), byId.get(row.id()));
        }
        Map<String, ClassSymbol> bySourceName = new HashMap<>();
        Map<String, List<ClassSymbol>> memberTypes = new HashMap<>();
        for (ClassSymbol symbol : types) {
            String enclosing = enclosingBinaryName(symbol, byBinaryName);
            if (enclosing != null && byBinaryName.get(symbol.binaryName()).id() == symbol.id()) {
                memberTypes.computeIfAbsent(enclosing, _ -> new ArrayList<>()).add(symbol);
                bySourceName.putIfAbsent(sourceName(symbol, byBinaryName), symbol);
            }
        }
        memberTypes.replaceAll((_, values) -> List.copyOf(values));
        Map<Long, List<FieldSymbol>> fields = new HashMap<>();
        for (SymbolIndexData.FieldRow row : data.fields().stream().sorted(Comparator.comparingInt(SymbolIndexData.FieldRow::ordinal).thenComparingLong(SymbolIndexData.FieldRow::id)).toList()) {
            fields.computeIfAbsent(row.typeId(), _ -> new ArrayList<>()).add(new FieldSymbol(row.id(), row.typeId(), row.ordinal(), row.name(), row.type(), modifiers(row.modifiers()), row.startOffset(), row.endOffset(), row.startLine(), row.endLine()));
        }
        Map<Long, List<MethodSymbol>> methods = new HashMap<>();
        for (SymbolIndexData.MethodRow row : data.methods().stream().sorted(Comparator.comparingInt(SymbolIndexData.MethodRow::ordinal).thenComparingLong(SymbolIndexData.MethodRow::id)).toList()) {
            methods.computeIfAbsent(row.typeId(), _ -> new ArrayList<>()).add(new MethodSymbol(row.id(), row.typeId(), row.ordinal(), row.name(), row.descriptor(), Optional.ofNullable(row.returnType()), modifiers(row.modifiers()), row.constructor(), row.startOffset(), row.endOffset(), row.startLine(), row.endLine()));
        }
        Map<Long, List<ParameterSymbol>> parameters = new HashMap<>();
        for (SymbolIndexData.ParameterRow row : data.parameters().stream().sorted(Comparator.comparingInt(SymbolIndexData.ParameterRow::ordinal).thenComparingLong(SymbolIndexData.ParameterRow::id)).toList()) {
            parameters.computeIfAbsent(row.methodId(), _ -> new ArrayList<>()).add(new ParameterSymbol(row.id(), row.methodId(), row.ordinal(), row.name(), row.type(), row.varargs(), row.startOffset(), row.endOffset(), row.startLine(), row.endLine()));
        }
        fields.replaceAll((_, values) -> List.copyOf(values));
        methods.replaceAll((_, values) -> List.copyOf(values));
        parameters.replaceAll((_, values) -> List.copyOf(values));
        subclasses.replaceAll((_, values) -> List.copyOf(values));
        implementors.replaceAll((_, values) -> List.copyOf(values));
        return new SymbolIndex(orderedPackages, List.copyOf(types), List.copyOf(lowerPackageNames), List.copyOf(lowerSimpleNames), byBinaryName, bySourceName, memberTypes, fields, methods, parameters, subclasses, implementors);
    }

    /**
     * A member type's binary name is its enclosing type's binary name, {@code $} and its own simple name.
     */
    private static String enclosingBinaryName(ClassSymbol symbol, Map<String, ClassSymbol> byBinaryName) {
        String suffix = "$" + symbol.simpleName();
        if (!symbol.binaryName().endsWith(suffix)) {
            return null;
        }
        String enclosing = symbol.binaryName().substring(0, symbol.binaryName().length() - suffix.length());
        return byBinaryName.containsKey(enclosing) ? enclosing : null;
    }

    private static String sourceName(ClassSymbol symbol, Map<String, ClassSymbol> byBinaryName) {
        String enclosing = enclosingBinaryName(symbol, byBinaryName);
        return enclosing == null ? symbol.binaryName() : sourceName(byBinaryName.get(enclosing), byBinaryName) + "." + symbol.simpleName();
    }

    private static int rank(String namespace) {
        return SourceNamespace.MINECRAFT.wireName().equals(namespace) ? 0 : 1;
    }

    private static Set<Modifier> modifiers(String text) {
        EnumSet<Modifier> values = EnumSet.noneOf(Modifier.class);
        if (!text.isEmpty()) {
            for (String value : text.split(",")) {
                values.add(Modifier.valueOf(value.toUpperCase(Locale.ROOT)));
            }
        }
        return values;
    }

    private static <T> List<T> first(List<T> values, int limit) {
        return values.size() <= limit ? values : List.copyOf(values.subList(0, Math.max(limit, 0)));
    }

    public List<ClassSymbol> classesUnder(String packageName, int limit) {
        String exact = packageName.toLowerCase(Locale.ROOT);
        String prefix = exact + ".";
        List<ClassSymbol> values = new ArrayList<>();
        for (int index = 0; index < types.size() && values.size() < limit; index++) {
            String candidate = lowerPackageNames.get(index);
            if (candidate.equals(exact) || candidate.startsWith(prefix)) {
                values.add(types.get(index));
            }
        }
        return List.copyOf(values);
    }

    public PackageListing packages(String namespace, int limit) {
        List<String> names = packages.stream().filter(row -> namespace == null || namespace.equals(row.namespace())).map(SymbolIndexData.PackageRow::name).toList();
        return new PackageListing(first(names, limit), names.size());
    }

    /**
     * Finds a type by its binary name, or a member type by the dotted name it has in source.
     */
    public ClassSymbol classByName(String name) {
        ClassSymbol exact = byBinaryName.get(name);
        return exact != null ? exact : bySourceName.get(name);
    }

    public Optional<ClassSymbol> enclosingType(ClassSymbol symbol) {
        return Optional.ofNullable(enclosingBinaryName(symbol, byBinaryName)).map(byBinaryName::get);
    }

    /**
     * The types declared directly inside the named type, in declaration order.
     */
    public List<ClassSymbol> memberTypes(String binaryName) {
        return memberTypes.getOrDefault(binaryName, List.of());
    }

    /**
     * Types whose simple name is the last segment of the given name, compared without regard to case.
     */
    public List<ClassSymbol> classesNamedLike(String name, int limit) {
        int separator = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        String simpleName = name.substring(separator + 1).toLowerCase(Locale.ROOT);
        List<ClassSymbol> values = new ArrayList<>();
        for (int index = 0; index < types.size() && values.size() < limit; index++) {
            if (!simpleName.isEmpty() && lowerSimpleNames.get(index).equals(simpleName)) {
                values.add(types.get(index));
            }
        }
        return List.copyOf(values);
    }

    public List<FieldSymbol> fields(long typeId) {
        return fields.getOrDefault(typeId, List.of());
    }

    public List<MethodSymbol> methods(long typeId) {
        return methods.getOrDefault(typeId, List.of());
    }

    /**
     * Every overload with exactly this name; when there is none, every overload of the first method
     * whose name differs from it only in case.
     */
    public List<MethodSymbol> methodsNamed(long typeId, String name) {
        List<MethodSymbol> declared = methods(typeId);
        String exactName = declared.stream().map(MethodSymbol::name).filter(name::equals).findFirst().or(() -> declared.stream().map(MethodSymbol::name).filter(name::equalsIgnoreCase).findFirst()).orElse(null);
        return exactName == null ? List.of() : declared.stream().filter(method -> method.name().equals(exactName)).toList();
    }

    public List<ParameterSymbol> parameters(long methodId) {
        return parameters.getOrDefault(methodId, List.of());
    }

    public List<ClassSymbol> hierarchy(String binaryName, boolean subclassesOnly, int limit) {
        return first((subclassesOnly ? subclasses : implementors).getOrDefault(binaryName, List.of()), limit);
    }

    public List<SearchHit> search(String query, String kind, int limit) {
        if (kind != null && !kind.equals("class") && !kind.equals("field") && !kind.equals("method")) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        boolean classes = kind == null || kind.equals("class");
        boolean fieldHits = kind == null || kind.equals("field");
        boolean methodHits = kind == null || kind.equals("method");
        List<SearchHit> hits = new ArrayList<>();
        for (int index = 0; index < types.size() && hits.size() < limit; index++) {
            ClassSymbol owner = types.get(index);
            List<FieldSymbol> ownerFields = fields(owner.id());
            List<MethodSymbol> ownerMethods = methods(owner.id());
            int fieldCount = classes ? ownerFields.size() : 0;
            int methodCount = classes ? ownerMethods.size() : 0;
            if (classes && lowerSimpleNames.get(index).contains(needle)) {
                hits.add(new SearchHit(SearchHitKind.CLASS, owner, Optional.empty(), Optional.empty(), List.of(), fieldCount, methodCount));
            }
            if (fieldHits) {
                for (FieldSymbol field : ownerFields) {
                    if (hits.size() >= limit) {
                        break;
                    }
                    if (field.name().toLowerCase(Locale.ROOT).contains(needle)) {
                        hits.add(new SearchHit(SearchHitKind.FIELD, owner, Optional.of(field), Optional.empty(), List.of(), fieldCount, methodCount));
                    }
                }
            }
            if (methodHits) {
                for (MethodSymbol method : ownerMethods) {
                    if (hits.size() >= limit) {
                        break;
                    }
                    if (method.name().toLowerCase(Locale.ROOT).contains(needle)) {
                        hits.add(new SearchHit(SearchHitKind.METHOD, owner, Optional.empty(), Optional.of(method), parameters(method.id()), fieldCount, methodCount));
                    }
                }
            }
        }
        return List.copyOf(hits);
    }
}
