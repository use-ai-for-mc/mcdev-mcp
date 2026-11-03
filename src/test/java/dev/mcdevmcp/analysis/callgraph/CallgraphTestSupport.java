package dev.mcdevmcp.analysis.callgraph;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class CallgraphTestSupport {
    private static final List<String> DEBUG_SOURCES = List.of("Base.java", "Contract.java", "Target.java", "Fixture.java");

    private CallgraphTestSupport() {
    }

    static Fixture compile(Path root) throws IOException {
        Path sources = root.resolve("sources");
        Path classes = root.resolve("classes");
        Files.createDirectories(sources);
        Files.createDirectories(classes);
        List<Path> debugSources = new ArrayList<>();
        for (String name : DEBUG_SOURCES) {
            Path source = copyResource(sources, name);
            debugSources.add(source);
        }
        compile(classes, List.of("--release", "25", "-g:lines,source", "-d", classes.toString()), debugSources);
        Path noLines = copyResource(sources, "NoLines.java");
        compile(classes, List.of("--release", "25", "-g:none", "-classpath", classes.toString(), "-d", classes.toString()), List.of(noLines));

        Map<String, byte[]> classBytes = new TreeMap<>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                classBytes.put(name, Files.readAllBytes(file));
            }
        }
        Path jar = root.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : classBytes.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
            JarEntry moduleInfo = new JarEntry("module-info.class");
            moduleInfo.setTime(0);
            output.putNextEntry(moduleInfo);
            output.write(new byte[]{0, 1, 2, 3});
            output.closeEntry();
        }
        return new Fixture(jar, Map.copyOf(classBytes));
    }

    private static Path copyResource(Path sources, String name) throws IOException {
        Path target = sources.resolve(name);
        String resource = "callgraph/fixture/" + name;
        try (InputStream input = CallgraphTestSupport.class.getClassLoader().getResourceAsStream(resource)) {
            Files.copy(Objects.requireNonNull(input, resource), target);
        }
        return target;
    }

    private static void compile(Path classes, List<String> options, List<Path> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Tests require a JDK compiler");
        }
        List<String> arguments = new ArrayList<>(options);
        sources.stream().sorted(Comparator.comparing(Path::toString)).map(Path::toString).forEach(arguments::add);
        int result = compiler.run(null, null, null, arguments.toArray(String[]::new));
        if (result != 0) {
            throw new IOException("Fixture compilation failed with exit code " + result + " into " + classes);
        }
    }

    record Fixture(Path jar, Map<String, byte[]> classBytes) {
        byte[] bytes(String binaryName) {
            return Objects.requireNonNull(classBytes.get(binaryName.replace('.', '/') + ".class"), binaryName);
        }
    }
}
