package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassDescriptors;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

record CompilerClasspath(List<CompilerClassFile> classes) {
    private CompilerClasspath(Map<String, CompilerClassFile> classes) {
        TreeMap<String, CompilerClassFile> sorted = new TreeMap<>(classes);
        this(List.copyOf(sorted.values()));
    }

    private static final List<String> BUILTIN_ANNOTATIONS = List.of("org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull", "org.jetbrains.annotations.Contract", "org.jetbrains.annotations.Range", "org.jetbrains.annotations.NonNls", "org.jetbrains.annotations.UnknownNullability", "org.jetbrains.annotations.ApiStatus", "org.jetbrains.annotations.ApiStatus$Internal", "org.jetbrains.annotations.ApiStatus$Experimental", "org.jetbrains.annotations.ApiStatus$ScheduledForRemoval", "org.jetbrains.annotations.ApiStatus$NonExtendable", "org.jetbrains.annotations.ApiStatus$OverrideOnly", "org.jetbrains.annotations.ApiStatus$AvailableSince", "org.jetbrains.annotations.ApiStatus$Obsolete", "org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull", "org.jspecify.annotations.NullMarked", "org.jspecify.annotations.NullUnmarked", "javax.annotation.Nullable", "javax.annotation.Nonnull", "javax.annotation.CheckForNull", "javax.annotation.CheckReturnValue", "javax.annotation.ParametersAreNonnullByDefault", "javax.annotation.concurrent.Immutable", "javax.annotation.concurrent.ThreadSafe", "javax.annotation.concurrent.NotThreadSafe", "javax.annotation.concurrent.GuardedBy", "javax.annotation.meta.TypeQualifierDefault");

    static CompilerClasspath read(IndexRequest request) throws IOException, InterruptedException {
        Map<String, CompilerClassFile> classes = new LinkedHashMap<>();
        List<Path> entries = new ArrayList<>(request.classpath().size() + 1);
        entries.add(request.remappedJar());
        entries.addAll(request.classpath());
        for (Path entry : entries) {
            readJar(entry, request.cancellation(), classes);
        }
        registerBuiltinAnnotations(classes);
        return new CompilerClasspath(classes);
    }

    private static void registerBuiltinAnnotations(Map<String, CompilerClassFile> classes) {
        ClassFile classFile = ClassFile.of();
        ClassDesc apiStatusDesc = ClassDesc.of("org.jetbrains.annotations.ApiStatus");
        List<String> apiStatusInners = List.of("Internal", "Experimental", "ScheduledForRemoval", "NonExtendable", "OverrideOnly", "AvailableSince", "Obsolete");
        List<InnerClassInfo> apiStatusInnerInfos = new ArrayList<>();
        for (String innerName : apiStatusInners) {
            apiStatusInnerInfos.add(InnerClassInfo.of(ClassDesc.of("org.jetbrains.annotations.ApiStatus$" + innerName), java.util.Optional.of(apiStatusDesc), java.util.Optional.of(innerName), AccessFlag.PUBLIC.mask() | AccessFlag.STATIC.mask() | AccessFlag.INTERFACE.mask() | AccessFlag.ANNOTATION.mask() | AccessFlag.ABSTRACT.mask()));
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

    private static void readJar(Path jar, Cancellation cancellation, Map<String, CompilerClassFile> classes) throws IOException, InterruptedException {
        ClassFile classFile = ClassFile.of();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                cancellation.throwIfCancelled();
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                var model = (ClassModel) null;
                try {
                    model = classFile.parse(bytes);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (model.isModuleInfo()) {
                    continue;
                }
                String binaryName = ClassDescriptors.binaryName(model.thisClass().asSymbol());
                String packageName = binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
                classes.putIfAbsent(binaryName, new CompilerClassFile(binaryName, packageName, bytes));
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unable to read compiler classpath entry " + jar, exception);
        }
    }
}