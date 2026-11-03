package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.SourceRoot;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

final class MemorySourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> implements StandardJavaFileManager {
    private final List<MemorySourceFileObject> sources;
    private final List<MemorySourceFileObject> sourcePathTypes;
    private final Map<String, MemorySourceFileObject> primaryTypes;
    private final List<MemoryInputClassFileObject> classes;
    private final Map<String, MemoryInputClassFileObject> classesByBinaryName;
    private final Map<Location, List<Path>> configuredLocations = new HashMap<>();

    MemorySourceFileManager(StandardJavaFileManager delegate, SourceCorpus corpus, CompilerClasspath classpath, IndexRequest request) {
        super(delegate);
        sources = corpus.sources().stream().map(MemorySourceFileObject::new).toList();
        Map<String, MemorySourceFileObject> indexed = new HashMap<>();
        List<MemorySourceFileObject> listed = new ArrayList<>();
        for (MemorySourceFileObject source : sources) {
            DecodedSource decoded = source.source();
            for (String simpleName : decoded.topLevelNames()) {
                String binaryName = decoded.packageName().isEmpty() ? simpleName : decoded.packageName() + "." + simpleName;
                MemorySourceFileObject alias = new MemorySourceFileObject(decoded, binaryName);
                indexed.putIfAbsent(binaryName, alias);
                listed.add(alias);
            }
        }
        primaryTypes = Map.copyOf(indexed);
        sourcePathTypes = List.copyOf(listed);
        classes = classpath.classes().stream().map(MemoryInputClassFileObject::new).toList();
        Map<String, MemoryInputClassFileObject> indexedClasses = new HashMap<>();
        for (MemoryInputClassFileObject classFile : classes) {
            indexedClasses.put(classFile.classFile().binaryName(), classFile);
        }
        classesByBinaryName = Map.copyOf(indexedClasses);
        configuredLocations.put(StandardLocation.SOURCE_PATH, request.sourceRoots().stream().map(SourceRoot::path).toList());
        List<Path> classpathPaths = new ArrayList<>(request.classpath().size() + 1);
        classpathPaths.add(request.remappedJar());
        classpathPaths.addAll(request.classpath());
        configuredLocations.put(StandardLocation.CLASS_PATH, List.copyOf(classpathPaths));
    }

    private static boolean matchesPackage(String candidate, String requested, boolean recurse) {
        return candidate.equals(requested) || recurse && candidate.startsWith(requested.isEmpty() ? "" : requested + ".");
    }

    MemorySourceFileObject object(DecodedSource source) {
        return sources.stream().filter(object -> object.source().uri().equals(source.uri())).findFirst().orElseThrow();
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        List<JavaFileObject> result = new ArrayList<>();
        if (location == StandardLocation.SOURCE_PATH && kinds.contains(JavaFileObject.Kind.SOURCE)) {
            for (MemorySourceFileObject source : sourcePathTypes) {
                if (matchesPackage(source.source().packageName(), packageName, recurse)) {
                    result.add(source);
                }
            }
        }
        if (location == StandardLocation.CLASS_PATH && kinds.contains(JavaFileObject.Kind.CLASS)) {
            for (MemoryInputClassFileObject classFile : classes) {
                if (matchesPackage(classFile.classFile().packageName(), packageName, recurse)) {
                    result.add(classFile);
                }
            }
        }
        Set<JavaFileObject.Kind> delegatedKinds = EnumSet.copyOf(kinds);
        if (location == StandardLocation.SOURCE_PATH || location == StandardLocation.CLASS_PATH) {
            delegatedKinds.clear();
        }
        if (!delegatedKinds.isEmpty()) {
            for (JavaFileObject file : super.list(location, packageName, delegatedKinds, recurse)) {
                result.add(file);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
        if (location == StandardLocation.SOURCE_PATH && kind == JavaFileObject.Kind.SOURCE) {
            return primaryTypes.get(className);
        }
        if (location == StandardLocation.CLASS_PATH && kind == JavaFileObject.Kind.CLASS) {
            return classesByBinaryName.get(className);
        }
        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof MemorySourceFileObject memorySource) {
            return memorySource.binaryName();
        }
        if (file instanceof MemoryInputClassFileObject classFile) {
            return classFile.classFile().binaryName();
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public boolean hasLocation(Location location) {
        return configuredLocations.containsKey(location) || super.hasLocation(location);
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
        return configuredLocations.containsKey(location) ? null : super.getClassLoader(location);
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        if (configuredLocations.containsKey(location)) {
            return null;
        }
        return super.getFileForInput(location, packageName, relativeName);
    }

    @Override
    public boolean isSameFile(FileObject first, FileObject second) {
        if (first instanceof MemorySourceFileObject || first instanceof MemoryInputClassFileObject || second instanceof MemorySourceFileObject || second instanceof MemoryInputClassFileObject) {
            return first.toUri().equals(second.toUri());
        }
        return fileManager.isSameFile(first, second);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        return fileManager.getJavaFileObjectsFromFiles(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return fileManager.getJavaFileObjects(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        return fileManager.getJavaFileObjectsFromStrings(names);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return fileManager.getJavaFileObjects(names);
    }

    @Override
    public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        if (location == StandardLocation.SOURCE_PATH || location == StandardLocation.CLASS_PATH) {
            List<Path> paths = new ArrayList<>();
            files.forEach(file -> paths.add(file.toPath().toAbsolutePath().normalize()));
            configuredLocations.put(location, List.copyOf(paths));
        }
        else {
            fileManager.setLocation(location, files);
        }
    }

    @Override
    public void setLocationFromPaths(Location location, Collection<? extends Path> paths) throws IOException {
        if (location == StandardLocation.SOURCE_PATH || location == StandardLocation.CLASS_PATH) {
            configuredLocations.put(location, paths.stream().map(path -> path.toAbsolutePath().normalize()).toList());
        }
        else {
            fileManager.setLocationFromPaths(location, paths);
        }
    }

    @Override
    public Iterable<? extends File> getLocation(Location location) {
        List<Path> paths = configuredLocations.get(location);
        return paths == null ? fileManager.getLocation(location) : paths.stream().map(Path::toFile).toList();
    }

    @Override
    public Iterable<? extends Path> getLocationAsPaths(Location location) {
        List<Path> paths = configuredLocations.get(location);
        return paths == null ? fileManager.getLocationAsPaths(location) : paths;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        if (kind == JavaFileObject.Kind.CLASS) {
            return new MemoryClassFileObject(className);
        }
        return super.getJavaFileForOutput(location, className, kind, sibling);
    }

    @Override
    public void close() throws IOException {
        fileManager.flush();
    }
}
