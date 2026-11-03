package dev.mcdevmcp.storage.migration;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ExecutableBodies;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Syntax and class-file ownership checks, without attribution or dependency guessing. A syntax
 * error confined to an executable body is not a failure: a decompiler cannot express every body
 * as Java, and what it leaves there does not make the unit's declarations unreadable.
 */
public final class SourceCacheValidator {
    private static Map<String, RequiredSourceClass> emittedUnits(Map<String, RequiredSourceClass> classes, Map<String, ParsedSourceUnit> parsed) {
        Map<String, RequiredSourceClass> result = new TreeMap<>();
        for (RequiredSourceClass type : classes.values()) {
            RequiredSourceClass root = type;
            Set<String> visited = new HashSet<>();
            while (root.owner() != null && visited.add(root.binaryName()) && classes.containsKey(root.owner())) {
                root = classes.get(root.owner());
            }
            String unit = type.unit();
            if (unit != null && unit.equals(root.unit()) && root.topLevel()) {
                ParsedSourceUnit original = parsed.get(unit);
                boolean originalDeclaresRoot = original != null && original.packageName().equals(root.packageName()) && (root.packageInfo() || root.module() || original.declarations().contains(root.simpleName()));
                // Vineflower 1.12.0 Fernflower.getClassEntryName emits ROOT archive paths with a .java suffix.
                // SourceFile and InnerClasses/EnclosingMethod still prove grouping; this is only an output-name alias.
                String emitted = root.binaryName() + ".java";
                if (!originalDeclaresRoot && parsed.containsKey(emitted)) {
                    unit = emitted;
                }
            }
            result.put(type.binaryName(), new RequiredSourceClass(type.binaryName(), type.packageName(), type.simpleName(), unit, type.owner(), type.topLevel(), type.packageInfo(), type.module()));
        }
        return result;
    }

    private static Map<String, RequiredSourceClass> requiredClasses(Path remappedJar, Cancellation cancellation, List<String> diagnostics) throws IOException {
        Map<String, RequiredSourceClass> result = new TreeMap<>();
        try (ZipFile zip = new ZipFile(remappedJar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class")).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                checkCancellation(cancellation);
                ClassModel model;
                try (var stream = zip.getInputStream(entry)) {
                    model = ClassFile.of().parse(stream.readAllBytes());
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid class file in source input: " + entry.getName(), exception);
                }
                String binary = model.thisClass().asInternalName();
                if (!entry.getName().equals(binary + ".class")) {
                    diagnostics.add("Ambiguous class archive path: " + entry.getName() + " declares " + binary);
                }
                int separator = binary.lastIndexOf('/');
                String packagePath = separator < 0 ? "" : binary.substring(0, separator);
                String simple = binary.substring(separator + 1);
                String source = model.findAttribute(Attributes.sourceFile()).map(attribute -> attribute.sourceFile().stringValue()).orElse(null);
                String unit = null;
                if (!safeSourceName(source)) {
                    diagnostics.add("Missing or unsafe SourceFile metadata: " + binary + " (" + source + ")");
                }
                else {
                    unit = packagePath.isEmpty() ? source : packagePath + "/" + source;
                }
                List<InnerClassInfo> self = model.findAttribute(Attributes.innerClasses()).stream().flatMap(attribute -> attribute.classes().stream()).filter(info -> info.innerClass().asInternalName().equals(binary)).toList();
                String enclosing = model.findAttribute(Attributes.enclosingMethod()).map(attribute -> attribute.enclosingClass().asInternalName()).orElse(null);
                String owner = null;
                if (self.size() > 1) {
                    diagnostics.add("Ambiguous InnerClasses ownership: " + binary);
                }
                else if (self.size() == 1) {
                    owner = self.getFirst().outerClass().map(ClassEntry::asInternalName).orElse(null);
                    if (owner == null && enclosing == null) {
                        diagnostics.add("Inner class has no declaring or enclosing class: " + binary);
                    }
                }
                if (owner != null && enclosing != null && !owner.equals(enclosing)) {
                    diagnostics.add("Conflicting InnerClasses and EnclosingMethod ownership: " + binary);
                }
                if (owner == null) {
                    owner = enclosing;
                }
                boolean module = model.isModuleInfo();
                boolean packageInfo = simple.equals("package-info");
                if (module && !"module-info.java".equals(source) || packageInfo && !"package-info.java".equals(source)) {
                    diagnostics.add("Descriptor SourceFile does not match descriptor kind: " + binary);
                }
                RequiredSourceClass value = new RequiredSourceClass(binary, packagePath.replace('/', '.'), simple, unit, owner, self.isEmpty() && enclosing == null, packageInfo, module);
                if (result.putIfAbsent(binary, value) != null) {
                    diagnostics.add("Duplicate class-file declaration: " + binary);
                }
            }
        }
        if (result.isEmpty()) {
            diagnostics.add("Remapped JAR contains no class files");
        }
        return result;
    }

    private static boolean safeSourceName(String source) {
        if (source == null || !source.endsWith(".java") || source.indexOf('/') >= 0 || source.indexOf('\\') >= 0 || source.indexOf(':') >= 0 || source.indexOf('\0') >= 0) {
            return false;
        }
        try {
            Path path = Path.of(source);
            return !path.isAbsolute() && path.getNameCount() == 1 && path.toString().equals(source);
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static void verifyOwner(RequiredSourceClass type, Map<String, RequiredSourceClass> classes, List<String> diagnostics) {
        Set<String> visited = new HashSet<>();
        RequiredSourceClass current = type;
        while (current.owner() != null) {
            if (!visited.add(current.binaryName())) {
                diagnostics.add("Cyclic class-file source ownership: " + type.binaryName());
                return;
            }
            RequiredSourceClass owner = classes.get(current.owner());
            if (owner == null || owner.unit() == null || !owner.unit().equals(type.unit())) {
                diagnostics.add("Unverified declaring compilation unit: " + type.binaryName() + " -> " + current.owner());
                return;
            }
            current = owner;
        }
        if (!current.topLevel() && !current.module() && !current.packageInfo()) {
            diagnostics.add("No top-level source owner: " + type.binaryName());
        }
    }

    private static Map<String, ParsedSourceUnit> parse(Path root, List<Path> sources, Cancellation cancellation, List<String> diagnostics) throws IOException {
        Map<String, ParsedSourceUnit> result = new TreeMap<>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK compiler is required to validate cached sources");
        }
        // Bound compiler retention while still parsing every emitted and external Java file.
        for (int offset = 0; offset < sources.size(); offset += 128) {
            checkCancellation(cancellation);
            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
            Map<URI, ExecutableBodies> bodies = new HashMap<>();
            try (var manager = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
                var files = manager.getJavaFileObjectsFromPaths(sources.subList(offset, Math.min(offset + 128, sources.size())));
                JavacTask task = (JavacTask) compiler.getTask(null, manager, collector, List.of("-proc:none", "--release", "25", "-encoding", "UTF-8", "-Xmaxerrs", "0", "-Xmaxwarns", "0"), null, files);
                SourcePositions positions = Trees.instance(task).getSourcePositions();
                for (CompilationUnitTree tree : task.parse()) {
                    checkCancellation(cancellation);
                    bodies.put(tree.getSourceFile().toUri(), ExecutableBodies.of(tree, positions));
                    String relative = root.relativize(Path.of(tree.getSourceFile().toUri()).toAbsolutePath().normalize()).toString().replace('\\', '/');
                    Set<String> declarations = new TreeSet<>();
                    for (var declaration : tree.getTypeDecls()) {
                        if (declaration instanceof ClassTree type) {
                            declarations.add(type.getSimpleName().toString());
                        }
                    }
                    result.put(relative, new ParsedSourceUnit(tree.getPackageName() == null ? "" : tree.getPackageName().toString(), Set.copyOf(declarations), tree.getModule() != null));
                }
            }
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                ExecutableBodies unitBodies = diagnostic.getSource() == null ? ExecutableBodies.none() : bodies.getOrDefault(diagnostic.getSource().toUri(), ExecutableBodies.none());
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR && unitBodies.escapedBy(diagnostic)) {
                    diagnostics.add((diagnostic.getSource() == null ? "<compiler>" : diagnostic.getSource().getName()) + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + ": " + diagnostic.getMessage(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private static void checkCancellation(Cancellation cancellation) throws InterruptedIOException {
        try {
            cancellation.throwIfCancelled();
        } catch (InterruptedException exception) {
            throw new InterruptedIOException("Source validation cancelled");
        }
    }

    public SourceValidation validate(Path sourceRoot, Path remappedJar, Cancellation cancellation) throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        SourceTreeInventory inventory = SourceTreeInventory.capture(root, cancellation);
        if (!inventory.present()) {
            return new SourceValidation(SourceValidationStatus.MISSING, List.of("Source root is missing: " + root), List.of(), List.of());
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source root must be a directory: " + root);
        }
        SourceArtifactIdentity input = SourceArtifactIdentity.capture(remappedJar, cancellation);
        List<String> unverified = new ArrayList<>();
        Map<String, RequiredSourceClass> classes = requiredClasses(remappedJar, cancellation, unverified);
        for (RequiredSourceClass type : classes.values()) {
            verifyOwner(type, classes, unverified);
        }
        List<Path> sources = inventory.entries().stream().filter(entry -> entry.kind() == SourceEntryKind.FILE && entry.relativePath().endsWith(".java")).map(entry -> root.resolve(entry.relativePath())).toList();
        List<String> invalid = new ArrayList<>();
        Map<String, ParsedSourceUnit> parsed = parse(root, sources, cancellation, invalid);
        classes = emittedUnits(classes, parsed);
        Set<String> required = new TreeSet<>();
        for (RequiredSourceClass type : classes.values()) {
            if (type.unit() != null) {
                required.add(type.unit());
            }
        }
        for (String unit : required) {
            if (!parsed.containsKey(unit)) {
                invalid.add("Missing required compilation unit: " + unit);
            }
        }
        for (RequiredSourceClass type : classes.values()) {
            if (type.unit() == null) {
                continue;
            }
            ParsedSourceUnit unit = parsed.get(type.unit());
            if (unit == null) {
                continue;
            }
            if (type.module()) {
                if (!unit.module()) {
                    invalid.add("Missing module declaration: " + type.unit());
                }
            }
            else if (!unit.packageName().equals(type.packageName())) {
                invalid.add("Package does not match class-file ownership: " + type.unit() + " expected " + type.packageName());
            }
            else if (type.topLevel() && !type.packageInfo() && !unit.declarations().contains(type.simpleName())) {
                invalid.add("Missing top-level declaration " + type.binaryName() + " in " + type.unit());
            }
        }
        if (!SourceTreeInventory.capture(root, cancellation).equals(inventory) || !SourceArtifactIdentity.capture(remappedJar, cancellation).equals(input)) {
            throw new IOException("Source tree or remapped JAR changed during source validation");
        }
        List<String> diagnostics = new ArrayList<>(invalid);
        diagnostics.addAll(unverified);
        SourceValidationStatus status = !invalid.isEmpty() ? SourceValidationStatus.INVALID : !unverified.isEmpty() ? SourceValidationStatus.UNVERIFIED_COMPLETENESS : SourceValidationStatus.VALID;
        return new SourceValidation(status, diagnostics, List.copyOf(required), List.copyOf(parsed.keySet()));
    }

}