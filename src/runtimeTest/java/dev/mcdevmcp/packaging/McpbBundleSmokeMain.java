package dev.mcdevmcp.packaging;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static dev.mcdevmcp.packaging.PackagedLauncher.require;

/**
 * Starts an unpacked MCPB bundle exactly as its manifest tells a client to, and requires the
 * server that answers to be the one the manifest describes.
 */
final class McpbBundleSmokeMain {
    private static final String BUNDLE_DIRECTORY = "${__dirname}";
    private static final TypeRef<Map<String, Object>> OBJECT = new TypeRef<>() {
    };

    private McpbBundleSmokeMain() {
    }

    static void main(String[] arguments) throws Exception {
        Path work = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path bundle = PackagedLauncher.install(Path.of(arguments[0]).toAbsolutePath().normalize(), work);
        Map<String, Object> manifest = McpJsonDefaults.getMapper().readValue(Files.readString(bundle.resolve("manifest.json"), StandardCharsets.UTF_8), OBJECT);
        Map<?, ?> server = object(manifest.get("server"), "server");
        Map<?, ?> configuration = object(server.get("mcp_config"), "server.mcp_config");

        require(object(manifest.get("compatibility"), "compatibility").get("platforms") instanceof List<?> platforms && platforms.equals(List.of(operatingSystem())), "The bundle is not declared for " + operatingSystem() + " alone");
        Path launcher = Path.of(text(configuration.get("command"), "command").replace(BUNDLE_DIRECTORY, bundle.toString()));
        require(launcher.equals(bundle.resolve(text(server.get("entry_point"), "entry_point"))), "The command and the entry point name different files");
        require(Files.isRegularFile(launcher), "The bundle has no launcher at " + launcher);

        List<String> command = Stream.concat(Stream.of(launcher.toString()), strings(configuration.get("args")).stream()).toList();
        var sessions = PackagedLauncher.coldThenCached(work, bundle, command, environment(object(configuration.get("env"), "env"), work));

        List<String> catalog = strings(manifest.get("tools")).stream().sorted().toList();
        for (var exchange : List.of(sessions.cold(), sessions.cached())) {
            require(exchange.serverName().equals(manifest.get("name")), "The server calls itself " + exchange.serverName());
            require(exchange.serverVersion().equals(manifest.get("version")), "The server reports version " + exchange.serverVersion());
            require(exchange.tools().equals(catalog), "The server lists other tools than the manifest: " + exchange.tools());
        }
        System.out.printf("MCPB bundle answered initialize after %d ms without a startup cache and %d ms with one.%n", sessions.cold().answeredMillis(), sessions.cached().answeredMillis());
    }

    /**
     * What a client makes of the user's settings. The two that enable optional tools are filled
     * in, so that the server lists its whole catalog; the others stay the placeholders they are
     * in the manifest, which is how a client passes on a setting its user left empty.
     */
    private static Map<String, String> environment(Map<?, ?> configured, Path work) throws IOException {
        Map<String, String> settings = Map.of("${user_config.script_logs}", Files.createDirectories(work.resolve("script-logs")).toString(), "${user_config.run_command}", "true");
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : configured.entrySet()) {
            String value = text(entry.getValue(), "env value");
            environment.put(text(entry.getKey(), "env name"), settings.getOrDefault(value, value));
        }
        return environment;
    }

    private static String operatingSystem() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "win32";
        }
        return name.contains("mac") ? "darwin" : "linux";
    }

    private static Map<?, ?> object(Object value, String field) {
        if (value instanceof Map<?, ?> object) {
            return object;
        }
        throw new IllegalStateException("The manifest has no " + field + " object");
    }

    private static String text(Object value, String field) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("The manifest has no " + field + " text");
    }

    /**
     * The strings of a manifest array, or the names of the objects in it.
     */
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> elements)) {
            throw new IllegalStateException("The manifest has no array where one is required");
        }
        return elements.stream().map(element -> element instanceof Map<?, ?> named ? text(named.get("name"), "name") : text(element, "array element")).toList();
    }
}
