package dev.mcdevmcp.packaging;

import dev.mcdevmcp.analysis.decompile.MappingConverter;
import dev.mcdevmcp.analysis.decompile.MinecraftDecompiler;
import dev.mcdevmcp.analysis.decompile.MinecraftRemapper;
import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.RequiredRuntime;
import dev.mcdevmcp.support.ScopeOutcomes;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import reactor.util.annotation.NonNull;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

/**
 * Exercises release-critical behavior using only these precompiled harness classes and the exact shaded artifact.
 */
public final class RuntimeArtifactSmokeMain {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final TypeRef<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeRef<>() {
    };

    private RuntimeArtifactSmokeMain() {
    }

    static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: RuntimeArtifactSmokeMain <mcdev-mcp.jar>");
        }
        Path jar = Path.of(arguments[0]).toAbsolutePath().normalize();
        logAndVerifyRuntime();
        String version = verifyArchive(jar);
        verifyMergedServiceProviders(jar);
        verifySymbolIndex();
        verifyRemappingAndDecompilation();
        verifyJarCli(jar, version);
        verifyStdio(jar, version);
        System.out.print("RUNTIME_ARTIFACT_SMOKE_OK\n");
    }

    private static void logAndVerifyRuntime() {
        int feature = Runtime.version().feature();
        String vendor = System.getProperty("java.vendor", "").strip();
        String vm = System.getProperty("java.vm.name", "").strip();
        require(feature == RequiredRuntime.FEATURE, RequiredRuntime.previewRequirement() + ", got " + feature);
        require(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview"), "Runtime smoke requires --enable-preview");
        require(!vendor.isEmpty(), "Runtime vendor is unavailable");
        require(!vm.isEmpty(), "Runtime VM name is unavailable");
        System.out.printf(Locale.ROOT, "RUNTIME_JAVA feature=%d vendor=%s vm=%s%n", feature, vendor, vm);
    }

    private static String verifyArchive(Path jar) throws Exception {
        require(Files.isRegularFile(jar), "Shaded JAR does not exist: " + jar);
        try (JarFile archive = new JarFile(jar.toFile(), true)) {
            Manifest manifest = archive.getManifest();
            require(manifest != null, "Shaded JAR has no manifest");
            Attributes attributes = manifest.getMainAttributes();
            require("dev.mcdevmcp.app.Main".equals(attributes.getValue(Attributes.Name.MAIN_CLASS)), "Unexpected shaded JAR Main-Class");
            String version = attributes.getValue("Implementation-Version");
            require(version != null && !version.isBlank(), "Shaded JAR has no Implementation-Version");
            require(attributes.getValue("Enable-Native-Access") == null, "Shaded JAR unexpectedly requests native access");
            require(archive.stream().noneMatch(entry -> entry.getName().startsWith("org/h2/")), "Shaded JAR unexpectedly contains H2");
            require(archive.stream().noneMatch(entry -> isSignatureEntry(entry.getName())), "Shaded JAR retains a signed-archive signature file");
            require(archive.stream().noneMatch(entry -> entry.getName().startsWith("org/sqlite/")), "Shaded JAR unexpectedly contains SQLite");
            return version;
        }
    }

    private static boolean isSignatureEntry(String entryName) {
        String normalized = entryName.toUpperCase(Locale.ROOT);
        return normalized.startsWith("META-INF/") && (normalized.endsWith(".SF") || normalized.endsWith(".RSA") || normalized.endsWith(".DSA"));
    }

    private static void verifyMergedServiceProviders(Path jar) throws Exception {
        Map<String, Set<String>> declarations = readServiceDeclarations(jar);

        require(!declarations.isEmpty(), "Shaded JAR contains no merged service-provider declarations");
        ClassLoader classLoader = RuntimeArtifactSmokeMain.class.getClassLoader();
        for (var declaration : declarations.entrySet()) {
            require(!declaration.getValue().isEmpty(), "Empty service-provider declaration: " + declaration.getKey());
            Class<?> serviceType = Class.forName(declaration.getKey(), false, classLoader);
            ServiceLoader<?> loader = ServiceLoader.load(serviceType, classLoader);
            Set<String> loadedProviders = new LinkedHashSet<>();
            for (ServiceLoader.Provider<?> provider : loader.stream().toList()) {
                Object instance = provider.get();
                require(serviceType.isInstance(instance), "Provider " + provider.type().getName() + " is not a " + serviceType.getName());
                loadedProviders.add(provider.type().getName());
            }
            require(loadedProviders.equals(declaration.getValue()), "Service providers for " + declaration.getKey() + " differ: declared=" + declaration.getValue() + ", loaded=" + loadedProviders);
        }
    }

    private static Map<String, Set<String>> readServiceDeclarations(Path jar) throws IOException {
        Map<String, Set<String>> declarations = new LinkedHashMap<>();
        try (JarFile archive = new JarFile(jar.toFile(), true)) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String prefix = "META-INF/services/";
                if (entry.isDirectory() || !entry.getName().startsWith(prefix) || entry.getName().length() == prefix.length()) {
                    continue;
                }
                String serviceName = entry.getName().substring(prefix.length());
                var providers = declarations.computeIfAbsent(serviceName, _ -> new LinkedHashSet<>());
                try (var reader = new BufferedReader(new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        String providerName = (comment < 0 ? line : line.substring(0, comment)).strip();
                        if (!providerName.isEmpty()) {
                            providers.add(providerName);
                        }
                    }
                }
            }
        }

        return declarations;
    }

    private static void verifySymbolIndex() throws Exception {
        Path root = Files.createTempDirectory("mcdev-mcp-runtime-index");
        Path index = root.resolve("symbols.jsonl");
        try {
            SymbolIndexFiles.write(index, SymbolIndexData.of("1.21.5", root.toString(), "0".repeat(64), "2026-01-01T00:00:00Z", List.of(new SymbolIndexData.PackageRow(1, "minecraft", null, "smoke")), List.of(new SymbolIndexData.TypeRow(1, 1, "minecraft", null, "smoke.Smoke", "Smoke", "class", null, List.of(), "smoke/Smoke.java", 0, 0, 1, 1)), List.of(), List.of(), List.of()));
            require(new SymbolRepository(index).classByName("smoke.Smoke") != null, "Symbol index write and reopen smoke failed");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyRemappingAndDecompilation() throws Exception {
        Path root = Files.createTempDirectory("mcdev-mcp-runtime-analysis");
        try {
            JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "Runtime has no system Java compiler");
            Path source = root.resolve("a/a.java");
            Path classes = root.resolve("classes");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                                      package a;
                                      public class a {
                                          public int b = 41;
                                          public String c() { return "runtime-fixture"; }
                                      }
                                      """, StandardCharsets.UTF_8);
            require(compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()) == 0, "Unable to compile remapping fixture");

            Path input = root.resolve("input.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "a.a");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(input), manifest)) {
                addEntry(output, "a/a.class", Files.readAllBytes(classes.resolve("a/a.class")));
                addEntry(output, "assets/fixture/value.txt", "preserved-resource".getBytes(StandardCharsets.UTF_8));
            }

            Path proguard = root.resolve("client.txt");
            Files.writeString(proguard, """
                                        fixture.Named -> a.a:
                                            int value -> b
                                            java.lang.String message() -> c
                                        """, StandardCharsets.UTF_8);
            Path mappings = new MappingConverter().convert(proguard, root.resolve("client.tiny"));
            Path remapped = new MinecraftRemapper(2).remap(input, mappings, root.resolve("remapped.jar"));

            try (JarFile archive = new JarFile(remapped.toFile(), true);
                 URLClassLoader loader = new URLClassLoader(new URL[]{remapped.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                require(archive.getEntry("fixture/Named.class") != null, "Tiny Remapper did not rename the fixture class");
                require("fixture.Named".equals(archive.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS)), "Tiny Remapper did not rewrite Main-Class");
                try (var resource = loader.getResourceAsStream("assets/fixture/value.txt")) {
                    require(resource != null && "preserved-resource".equals(new String(resource.readAllBytes(), StandardCharsets.UTF_8)), "Tiny Remapper did not preserve the fixture resource");
                }
                Class<?> type = Class.forName("fixture.Named", true, loader);
                Object instance = type.getConstructor().newInstance();
                require(type.getField("value").getInt(instance) == 41, "Remapped field is not loadable");
                require("runtime-fixture".equals(type.getMethod("message").invoke(instance)), "Remapped method is not loadable");
            }

            Path sources = new MinecraftDecompiler().decompile(remapped, root.resolve("sources"));
            Path generated = sources.resolve("fixture/Named.java");
            require(Files.isRegularFile(generated), "Vineflower did not generate fixture/Named.java");
            String generatedJava = Files.readString(generated, StandardCharsets.UTF_8);
            require(generatedJava.contains("class Named"), "Vineflower output does not declare Named");
            require(generatedJava.contains("message()") && generatedJava.contains("runtime-fixture"), "Vineflower output does not contain the fixture method");
            require(compiler.run(null, null, null, "--release", "21", "-d", root.resolve("decompiled-classes").toString(), generated.toString()) == 0, "Generated Vineflower Java does not compile");
        } finally {
            deleteTree(root);
        }
    }

    private static void addEntry(JarOutputStream output, String name, byte[] contents) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(contents);
        output.closeEntry();
    }

    @SuppressWarnings("preview")
    private static void verifyJarCli(Path jar, String version) throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "--enable-preview", "-jar", jar.toString(), "--version").start();
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(), config -> config.withName("runtime-artifact-cli").withTimeout(PROCESS_TIMEOUT))) {
            var output = scope.fork(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            var errors = scope.fork(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            var exit = scope.fork(() -> process.waitFor());
            scope.join();
            require(exit.get() == 0, "Exact JAR CLI exited with " + exit.get());
            require(version.equals(output.get().strip()), "Exact JAR CLI version differs from its manifest");
            require(errors.get().isBlank(), "Exact JAR CLI emitted diagnostics");
        } catch (ExecutionException exception) {
            if (ScopeOutcomes.timedOut(exception)) {
                throw new IllegalStateException("Exact JAR CLI did not stop before its deadline", exception);
            }
            throw exception;
        } finally {
            stopProcess(process);
        }
    }

    @SuppressWarnings("preview")
    private static void verifyStdio(Path jar, String version) throws Exception {
        Path runtimeHome = Files.createTempDirectory("mcdev-mcp-runtime-stdio");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "--enable-preview", "-Duser.home=" + runtimeHome, "-jar", jar.toString(), "serve");
        builder.environment().put("LOCALAPPDATA", runtimeHome.toString());
        builder.environment().put("XDG_CACHE_HOME", runtimeHome.toString());
        builder.environment().put("MCDEV_SESSION_LOG_DIR", runtimeHome.resolve("logs").toString());
        builder.environment().put("MCDEV_RUN_COMMAND", "true");
        Process process = builder.start();
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(), config -> config.withName("runtime-artifact-stdio"));
             var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            var errors = scope.fork(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            try {
                write(input, request(1, "initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "runtime-artifact-smoke", "version", "1"))));
                Map<String, Object> initialize = readResponse(output, "initialize");
                verifyResponseEnvelope(initialize, 1);
                Map<String, Object> initializeResult = requiredMap(initialize, "result");
                require("2024-11-05".equals(initializeResult.get("protocolVersion")), "Exact JAR negotiated an unexpected MCP protocol version");
                Map<String, Object> serverInfo = requiredMap(initializeResult, "serverInfo");
                require("mcdev-mcp".equals(serverInfo.get("name")), "Exact JAR initialize returned the wrong server name");
                require(version.equals(serverInfo.get("version")), "Exact JAR initialize version differs from its manifest");

                write(input, Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
                write(input, request(2, "tools/list", Map.of()));
                Map<String, Object> toolsResponse = readResponse(output, "tools/list");
                verifyResponseEnvelope(toolsResponse, 2);
                List<Map<String, Object>> actualTools = requiredTools(requiredMap(toolsResponse, "result"));
                try (var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), McpJsonDefaults.getMapper())) {
                    verifyToolCatalog(actualTools, composition.definitions());
                }

                process.getOutputStream().close();
                require(process.waitFor(PROCESS_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "Exact JAR STDIO server did not stop after stdin closed");
                require(process.exitValue() == 0, "Exact JAR STDIO server exited with " + process.exitValue());
                require(output.lines().toList().isEmpty(), "Exact JAR STDIO server emitted trailing protocol output");
            } finally {
                scope.join();
            }
            require(errors.get().isBlank(), "Exact JAR STDIO server emitted diagnostics");
        } finally {
            stopProcess(process);
            deleteTree(runtimeHome);
        }
    }

    static void verifyToolCatalog(List<Map<String, Object>> actualTools, List<ToolDefinition> definitions) throws IOException {
        List<Map<String, Object>> expectedTools = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.put("inputSchema", definition.inputSchema());
            definition.output().ifPresent(output -> tool.put("outputSchema", output.schema().value()));
            expectedTools.add(tool);
        }
        // Compare both sides as parsed wire JSON, including the mapper's numeric representation.
        var mapper = McpJsonDefaults.getMapper();
        expectedTools = mapper.readValue(mapper.writeValueAsString(expectedTools), LIST_OF_MAPS_TYPE);
        require(actualTools.equals(expectedTools), "Exact JAR tools/list differs from Java-owned tools metadata");
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> parameters) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", parameters);
    }

    private static void write(OutputStreamWriter writer, Map<String, Object> message) throws IOException {
        writer.write(McpJsonDefaults.getMapper().writeValueAsString(message));
        writer.write(System.lineSeparator());
        writer.flush();
    }

    @SuppressWarnings("preview")
    private static Map<String, Object> readResponse(BufferedReader output, String operation) throws Exception {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>anySuccessfulOrThrow(), config -> config.withName("runtime-artifact-read").withTimeout(PROCESS_TIMEOUT))) {
            scope.fork(output::readLine);
            String response = scope.join();
            require(response != null, "Exact JAR STDIO server closed before answering " + operation);
            return McpJsonDefaults.getMapper().readValue(response, MAP_TYPE);
        } catch (ExecutionException exception) {
            if (ScopeOutcomes.timedOut(exception)) {
                throw new IllegalStateException("Exact JAR did not answer " + operation + " before its deadline", exception);
            }
            throw exception;
        }
    }

    private static void verifyResponseEnvelope(Map<String, Object> response, int id) {
        require("2.0".equals(response.get("jsonrpc")), "MCP response has an unexpected jsonrpc version");
        require(response.get("id") instanceof Number number && number.intValue() == id, "MCP response has an unexpected id");
        require(!response.containsKey("error"), "MCP response contains an error: " + response.get("error"));
    }

    private static Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        require(value instanceof Map<?, ?>, "MCP response does not contain object " + key);
        return McpJsonDefaults.getMapper().convertValue(value, MAP_TYPE);
    }

    private static List<Map<String, Object>> requiredTools(Map<String, Object> source) {
        Object value = source.get("tools");
        require(value instanceof List<?>, "MCP response does not contain tools array");
        return McpJsonDefaults.getMapper().convertValue(value, LIST_OF_MAPS_TYPE);
    }

    private static void stopProcess(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                require(process.waitFor(5, TimeUnit.SECONDS), "Child process could not be terminated");
            }
        }
        require(!process.isAlive(), "Child process remains alive after teardown");
    }

    private static String javaExecutable() {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", suffix).toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}