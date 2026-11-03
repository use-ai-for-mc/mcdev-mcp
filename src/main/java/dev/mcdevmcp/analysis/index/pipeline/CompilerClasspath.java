package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassDescriptors;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.classfile.*;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

value record CompilerClasspath(List<CompilerClassFile> classes) {
    private static final List<String> BUILTIN_ANNOTATIONS = List.of("org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull", "org.jetbrains.annotations.Contract", "org.jetbrains.annotations.Range", "org.jetbrains.annotations.NonNls", "org.jetbrains.annotations.UnknownNullability", "org.jetbrains.annotations.ApiStatus", "org.jetbrains.annotations.ApiStatus$Internal", "org.jetbrains.annotations.ApiStatus$Experimental", "org.jetbrains.annotations.ApiStatus$ScheduledForRemoval", "org.jetbrains.annotations.ApiStatus$NonExtendable", "org.jetbrains.annotations.ApiStatus$OverrideOnly", "org.jetbrains.annotations.ApiStatus$AvailableSince", "org.jetbrains.annotations.ApiStatus$Obsolete", "org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull", "org.jspecify.annotations.NullMarked", "org.jspecify.annotations.NullUnmarked", "javax.annotation.Nullable", "javax.annotation.Nonnull", "javax.annotation.CheckForNull", "javax.annotation.CheckReturnValue", "javax.annotation.ParametersAreNonnullByDefault", "javax.annotation.concurrent.Immutable", "javax.annotation.concurrent.ThreadSafe", "javax.annotation.concurrent.NotThreadSafe", "javax.annotation.concurrent.GuardedBy", "javax.annotation.meta.TypeQualifierDefault");

    private CompilerClasspath(Map<String, CompilerClassFile> classes) {
        Map<String, CompilerClassFile> sorted = new TreeMap<>(classes);
        this(List.copyOf(sorted.values()));
    }

    static CompilerClasspath read(IndexRequest request) throws IOException, InterruptedException {
        Map<String, CompilerClassFile> classes = new LinkedHashMap<>();
        ReferencedAnnotations referenced = new ReferencedAnnotations();
        List<Path> entries = new ArrayList<>(request.classpath().size() + 1);
        entries.add(request.remappedJar());
        entries.addAll(request.classpath());
        for (Path entry : entries) {
            readJar(entry, request.cancellation(), classes, referenced);
        }
        registerBuiltinAnnotations(classes);
        referenced.registerMissing(classes);
        return new CompilerClasspath(classes);
    }

    private static void registerBuiltinAnnotations(Map<String, CompilerClassFile> classes) {
        ClassFile classFile = ClassFile.of();
        ClassDesc apiStatusDesc = ClassDesc.of("org.jetbrains.annotations.ApiStatus");
        List<String> apiStatusInners = List.of("Internal", "Experimental", "ScheduledForRemoval", "NonExtendable", "OverrideOnly", "AvailableSince", "Obsolete");
        List<InnerClassInfo> apiStatusInnerInfos = new ArrayList<>();
        for (String innerName : apiStatusInners) {
            apiStatusInnerInfos.add(InnerClassInfo.of(ClassDesc.of("org.jetbrains.annotations.ApiStatus$" + innerName), Optional.of(apiStatusDesc), Optional.of(innerName), AccessFlag.PUBLIC.mask() | AccessFlag.STATIC.mask() | AccessFlag.INTERFACE.mask() | AccessFlag.ANNOTATION.mask() | AccessFlag.ABSTRACT.mask()));
        }

        for (String binaryName : BUILTIN_ANNOTATIONS) {
            if (!classes.containsKey(binaryName)) {
                byte[] bytes = classFile.build(ClassDesc.of(binaryName), clb -> {
                    clb.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ANNOTATION, AccessFlag.ABSTRACT);
                    clb.withInterfaceSymbols(ClassDesc.of("java.lang.annotation.Annotation"));
                    if (binaryName.equals("org.jetbrains.annotations.Contract")) {
                        clb.withMethod("value", MethodTypeDesc.of(ClassDesc.of("java.lang.String")), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofString(""))));
                        clb.withMethod("pure", MethodTypeDesc.of(ConstantDescs.CD_boolean), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofBoolean(false))));
                        clb.withMethod("mutates", MethodTypeDesc.of(ClassDesc.of("java.lang.String")), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofString(""))));
                    }
                    else if (binaryName.equals("org.jetbrains.annotations.Range")) {
                        clb.withMethod("from", MethodTypeDesc.of(ConstantDescs.CD_long), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofLong(Long.MIN_VALUE))));
                        clb.withMethod("to", MethodTypeDesc.of(ConstantDescs.CD_long), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofLong(Long.MAX_VALUE))));
                    }
                    else if (binaryName.equals("javax.annotation.meta.TypeQualifierDefault")) {
                        clb.withMethod("value", MethodTypeDesc.of(ClassDesc.ofDescriptor("[Ljava/lang/annotation/ElementType;")), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), _ -> {
                        });
                    }
                    else if (binaryName.startsWith("org.jetbrains.annotations.ApiStatus")) {
                        clb.with(InnerClassesAttribute.of(apiStatusInnerInfos));
                        clb.withMethod("value", MethodTypeDesc.of(ClassDesc.of("java.lang.String")), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofString(""))));
                        clb.withMethod("inVersion", MethodTypeDesc.of(ClassDesc.of("java.lang.String")), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofString(""))));
                    }
                    else if (binaryName.equals("org.jetbrains.annotations.NonNls") || binaryName.equals("org.jetbrains.annotations.UnknownNullability") || binaryName.equals("org.jetbrains.annotations.Nullable") || binaryName.equals("org.jetbrains.annotations.NotNull") || binaryName.equals("javax.annotation.concurrent.GuardedBy")) {
                        clb.withMethod("value", MethodTypeDesc.of(ClassDesc.of("java.lang.String")), AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask(), mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofString(""))));
                    }
                });
                String packageName = binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
                classes.put(binaryName, new CompilerClassFile(binaryName, packageName, bytes));
            }
        }
    }

    private static void readJar(Path jar, Cancellation cancellation, Map<String, CompilerClassFile> classes, ReferencedAnnotations referenced) throws IOException, InterruptedException {
        ClassFile classFile = ClassFile.of();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                cancellation.throwIfCancelled();
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                ClassModel model;
                try {
                    model = classFile.parse(bytes);
                } catch (IllegalArgumentException _) {
                    continue;
                }
                if (model.isModuleInfo()) {
                    continue;
                }
                String binaryName = ClassDescriptors.binaryName(model.thisClass().asSymbol());
                String packageName = binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
                classes.putIfAbsent(binaryName, new CompilerClassFile(binaryName, packageName, bytes));
                try {
                    referenced.collect(model);
                } catch (IllegalArgumentException _) {
                    // A malformed annotation attribute only costs the stubs it would have named.
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unable to read compiler classpath entry " + jar, exception);
        }
    }

    // Annotation types that class files use but no classpath entry defines, such as compile-only
    // annotations a new Minecraft version starts to use. Javac rejects a source that names a type it
    // cannot find, so each one gets a stub that declares the elements its uses give it.
    private static final class ReferencedAnnotations {
        private static final int ANNOTATION_FLAGS = AccessFlag.PUBLIC.mask() | AccessFlag.INTERFACE.mask() | AccessFlag.ANNOTATION.mask() | AccessFlag.ABSTRACT.mask();
        private static final int CONTAINER_FLAGS = AccessFlag.PUBLIC.mask() | AccessFlag.INTERFACE.mask() | AccessFlag.ABSTRACT.mask();
        private static final int ELEMENT_FLAGS = AccessFlag.PUBLIC.mask() | AccessFlag.ABSTRACT.mask();

        private final Map<String, Map<String, AnnotationValue>> elements = new TreeMap<>();
        private final Set<String> typeUse = new HashSet<>();

        private static AnnotationValue moreSpecific(AnnotationValue known, AnnotationValue next) {
            // An empty array does not say what its elements are; any other value says what its element is.
            return known instanceof AnnotationValue.OfArray array && array.values().isEmpty() ? next : known;
        }

        private static ClassDesc type(AnnotationValue value) {
            return switch (value) {
                case AnnotationValue.OfString _ -> ConstantDescs.CD_String;
                case AnnotationValue.OfInt _ -> ConstantDescs.CD_int;
                case AnnotationValue.OfLong _ -> ConstantDescs.CD_long;
                case AnnotationValue.OfBoolean _ -> ConstantDescs.CD_boolean;
                case AnnotationValue.OfByte _ -> ConstantDescs.CD_byte;
                case AnnotationValue.OfChar _ -> ConstantDescs.CD_char;
                case AnnotationValue.OfShort _ -> ConstantDescs.CD_short;
                case AnnotationValue.OfFloat _ -> ConstantDescs.CD_float;
                case AnnotationValue.OfDouble _ -> ConstantDescs.CD_double;
                case AnnotationValue.OfClass _ -> ConstantDescs.CD_Class;
                case AnnotationValue.OfEnum enumValue -> enumValue.classSymbol();
                case AnnotationValue.OfAnnotation nested -> nested.annotation().classSymbol();
                case AnnotationValue.OfArray array -> (array.values().isEmpty() ? ConstantDescs.CD_String : type(array.values().getFirst())).arrayType();
            };
        }

        private static String outer(String binaryName) {
            int separator = binaryName.lastIndexOf('$');
            return separator < 0 ? null : binaryName.substring(0, separator);
        }

        private static InnerClassInfo memberInfo(String binaryName, int flags) {
            String outer = outer(binaryName);
            return InnerClassInfo.of(ClassDesc.of(binaryName), Optional.of(ClassDesc.of(outer)), Optional.of(binaryName.substring(outer.length() + 1)), flags | AccessFlag.STATIC.mask());
        }

        void collect(ClassModel model) {
            collectAttributes(model);
            for (FieldModel field : model.fields()) {
                collectAttributes(field);
            }
            for (MethodModel method : model.methods()) {
                collectAttributes(method);
                method.findAttribute(Attributes.runtimeVisibleParameterAnnotations()).ifPresent(attribute -> attribute.parameterAnnotations().forEach(annotations -> annotations.forEach(annotation -> add(annotation, false))));
                method.findAttribute(Attributes.runtimeInvisibleParameterAnnotations()).ifPresent(attribute -> attribute.parameterAnnotations().forEach(annotations -> annotations.forEach(annotation -> add(annotation, false))));
                method.code().ifPresent(this::collectAttributes);
            }
            model.findAttribute(Attributes.record()).ifPresent(record -> record.components().forEach(this::collectAttributes));
        }

        private void collectAttributes(AttributedElement element) {
            element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(annotation -> add(annotation, false)));
            element.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(annotation -> add(annotation, false)));
            element.findAttribute(Attributes.runtimeVisibleTypeAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(annotation -> add(annotation.annotation(), true)));
            element.findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(annotation -> add(annotation.annotation(), true)));
        }

        private void add(Annotation annotation, boolean asTypeUse) {
            String binaryName = ClassDescriptors.binaryName(annotation.classSymbol());
            Map<String, AnnotationValue> known = elements.computeIfAbsent(binaryName, _ -> new TreeMap<>());
            if (asTypeUse) {
                typeUse.add(binaryName);
            }
            for (AnnotationElement element : annotation.elements()) {
                known.merge(element.name().stringValue(), element.value(), ReferencedAnnotations::moreSpecific);
                addNested(element.value());
            }
        }

        private void addNested(AnnotationValue value) {
            switch (value) {
                case AnnotationValue.OfAnnotation nested -> add(nested.annotation(), false);
                case AnnotationValue.OfArray array -> array.values().forEach(this::addNested);
                default -> {
                }
            }
        }

        void registerMissing(Map<String, CompilerClassFile> classes) {
            Set<String> annotations = new TreeSet<>();
            Set<String> containers = new TreeSet<>();
            for (String binaryName : elements.keySet()) {
                if (classes.containsKey(binaryName)) {
                    continue;
                }
                annotations.add(binaryName);
                // Javac finds a nested type through its enclosing type, so a missing enclosing type
                // becomes an empty interface that lists it.
                for (String outer = outer(binaryName); outer != null && !classes.containsKey(outer); outer = outer(outer)) {
                    containers.add(outer);
                }
            }
            containers.removeAll(annotations);
            Set<String> stubs = new TreeSet<>(annotations);
            stubs.addAll(containers);
            ClassFile classFile = ClassFile.of();
            for (String binaryName : stubs) {
                boolean annotation = annotations.contains(binaryName);
                List<InnerClassInfo> innerClasses = new ArrayList<>();
                for (String outer = binaryName; outer(outer) != null; outer = outer(outer)) {
                    innerClasses.add(memberInfo(outer, annotations.contains(outer) ? ANNOTATION_FLAGS : CONTAINER_FLAGS));
                }
                for (String member : stubs) {
                    if (binaryName.equals(outer(member))) {
                        innerClasses.add(memberInfo(member, annotations.contains(member) ? ANNOTATION_FLAGS : CONTAINER_FLAGS));
                    }
                }
                byte[] bytes = classFile.build(ClassDesc.of(binaryName), clb -> {
                    clb.withFlags(annotation ? ANNOTATION_FLAGS : CONTAINER_FLAGS);
                    if (!innerClasses.isEmpty()) {
                        clb.with(InnerClassesAttribute.of(innerClasses));
                    }
                    if (!annotation) {
                        return;
                    }
                    clb.withInterfaceSymbols(ClassDesc.of("java.lang.annotation.Annotation"));
                    if (typeUse.contains(binaryName)) {
                        // Applicable wherever the class files put it, type uses included.
                        ClassDesc elementType = ClassDesc.of("java.lang.annotation.ElementType");
                        List<AnnotationValue> targets = Arrays.stream(ElementType.values()).<AnnotationValue>map(target -> AnnotationValue.ofEnum(elementType, target.name())).toList();
                        clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("java.lang.annotation.Target"), AnnotationElement.of("value", AnnotationValue.ofArray(targets)))));
                    }
                    elements.get(binaryName).forEach((name, value) -> clb.withMethod(name, MethodTypeDesc.of(type(value)), ELEMENT_FLAGS, mb -> mb.with(AnnotationDefaultAttribute.of(value))));
                });
                String packageName = binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
                classes.put(binaryName, new CompilerClassFile(binaryName, packageName, bytes));
            }
        }
    }
}