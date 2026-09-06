package dev.mcdevmcp.analysis.classfile;

import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClassFileTypeCatalog {
    private final NavigableMap<String, ClassFileType> types;

    private ClassFileTypeCatalog(Map<String, ClassFileType> types) {
        this.types = Collections.unmodifiableNavigableMap(new TreeMap<>(types));
    }

    public static ClassFileTypeCatalog read(Path jar) throws IOException {
        try {
            return read(jar, Cancellation.none());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Class-file catalog build was interrupted", exception);
        }
    }

    public static ClassFileTypeCatalog read(Path jar, Cancellation cancellation) throws IOException, InterruptedException {
        Path normalized = Objects.requireNonNull(jar, "jar").toAbsolutePath().normalize();
        Objects.requireNonNull(cancellation, "cancellation");
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Remapped JAR is not a regular file: " + normalized);
        }
        Map<String, ClassFileType> catalog = new HashMap<>();
        ClassFile classFile = ClassFile.of();
        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class")).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                cancellation.throwIfCancelled();
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                ClassModel model;
                try {
                    model = classFile.parse(bytes);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Unable to parse class-file entry " + entry.getName() + " from " + normalized, exception);
                }
                if (model.isModuleInfo()) {
                    continue;
                }
                ClassFileType type = toType(model);
                ClassFileType previous = catalog.putIfAbsent(type.binaryName(), type);
                if (previous != null) {
                    throw new IOException("Duplicate class-file binary name " + type.binaryName() + " in " + normalized);
                }
            }
        }
        return new ClassFileTypeCatalog(catalog);
    }

    private static ClassFileType toType(ClassModel model) {
        ClassDesc descriptor = model.thisClass().asSymbol();
        Optional<ClassDesc> superclass = model.superclass().map(ClassEntry::asSymbol);
        List<ClassDesc> interfaces = model.interfaces().stream().map(ClassEntry::asSymbol).toList();
        Optional<InnerClassInfo> nesting = model.findAttribute(Attributes.innerClasses()).stream().flatMap(attribute -> attribute.classes().stream()).filter(info -> info.innerClass().matches(descriptor)).findFirst();
        Optional<ClassDesc> outerClass = nesting.flatMap(InnerClassInfo::outerClass).map(ClassEntry::asSymbol);
        Optional<String> innerName = nesting.flatMap(InnerClassInfo::innerName).map(Utf8Entry::stringValue);
        Optional<ClassDesc> nestHost = model.findAttribute(Attributes.nestHost()).map(attribute -> attribute.nestHost().asSymbol());
        List<ClassDesc> nestMembers = model.findAttribute(Attributes.nestMembers()).stream().flatMap(attribute -> attribute.nestMembers().stream()).map(ClassEntry::asSymbol).toList();
        return new ClassFileType(descriptor, superclass, interfaces, model.flags().flags(), outerClass, innerName, nestHost, nestMembers);
    }

    public boolean contains(String binaryName) {
        return types.containsKey(Objects.requireNonNull(binaryName, "binaryName"));
    }

    public Optional<ClassFileType> find(String binaryName) {
        return Optional.ofNullable(types.get(Objects.requireNonNull(binaryName, "binaryName")));
    }

    public ClassFileType require(String binaryName) {
        return find(binaryName).orElseThrow(() -> new NoSuchElementException("Class-file type not found: " + binaryName));
    }

    @SuppressWarnings("unused")
    public List<ClassFileType> types() {
        return List.copyOf(types.values());
    }
}