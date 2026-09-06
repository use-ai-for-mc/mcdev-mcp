package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.JsonResourceReader;
import dev.mcdevmcp.support.JsonValues;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public final class ToolCatalog {
    private final AppEnvironment environment;
    private final McpJsonMapper mapper;
    private final List<ToolDefinition> definitions;
    private final Map<String, ToolDefinition> definitionsByName;
    private final Map<String, ToolBinding<?>> bindingsByName;

    private ToolCatalog(AppEnvironment environment, McpJsonMapper mapper, List<ToolDefinition> definitions, Map<String, ToolBinding<?>> bindings) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.definitions = List.copyOf(definitions);
        var byName = new HashMap<String, ToolDefinition>();
        for (var definition : definitions) {
            if (byName.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate tool metadata: " + definition.name());
            }
        }
        definitionsByName = Map.copyOf(byName);
        bindingsByName = Map.copyOf(bindings);
    }

    public static ToolCatalog load(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return fromDeclarations(environment, mapper, loadMetadata(mapper), ToolDeclarations.all(), Objects.requireNonNull(bindings, "bindings").entrySet());
    }

    public static ToolCatalog load(AppEnvironment environment, List<ToolDeclaration<?>> declarations, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return fromDeclarations(environment, mapper, loadMetadata(mapper), List.copyOf(Objects.requireNonNull(declarations, "declarations")), Objects.requireNonNull(bindings, "bindings").entrySet());
    }

    /**
     * Creates a catalog from an already-composed, metadata-ordered definition
     * list without rereading or regenerating its definitions.
     */
    public static ToolCatalog fromDefinitions(AppEnvironment environment, McpJsonMapper mapper, List<ToolDefinition> definitions, Map<String, ToolBinding<?>> bindings) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(bindings, "bindings");
        List<ToolDefinition> requiredDefinitions = List.copyOf(definitions);
        Map<String, ToolBinding<?>> collected = collectBindings(bindings.entrySet());
        Set<String> definitionNames = new LinkedHashSet<>();
        for (ToolDefinition definition : requiredDefinitions) {
            Objects.requireNonNull(definition, "Tool definition");
            if (!definitionNames.add(definition.name())) {
                throw new IllegalArgumentException("Duplicate tool definition: " + definition.name());
            }
            if (collected.get(definition.name()) == null) {
                throw new IllegalArgumentException("Missing tool binding: " + definition.name());
            }
            if (collected.get(definition.name()).input() != definition.input()) {
                throw new IllegalArgumentException("Tool binding input differs from its definition: " + definition.name());
            }
        }
        if (!definitionNames.equals(collected.keySet())) {
            throw new IllegalArgumentException("Tool definition and binding names differ");
        }
        return new ToolCatalog(environment, mapper, requiredDefinitions, collected);
    }

    public static ToolMetadata[] loadMetadata(McpJsonMapper mapper) {
        return new JsonResourceReader(Objects.requireNonNull(mapper, "mapper")).read("/mcp/tools.json", ToolMetadata[].class);
    }

    public static List<ToolDefinition> declarativeDefinitions(AppEnvironment environment, McpJsonMapper mapper, Map<String, ToolBinding<?>> bindings) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(bindings, "bindings");
        return fromDeclarations(environment, mapper, loadMetadata(mapper), ToolDeclarations.all(), bindings.entrySet()).definitions();
    }

    public static ToolCatalog load(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper, ExecutorService blockingExecutor) {
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        var adaptedBindings = new LinkedHashMap<String, ToolBinding<?>>();
        Objects.requireNonNull(bindings, "bindings").forEach((name, binding) -> adaptedBindings.put(name, binding.withBlockingExecutor(blockingExecutor)));
        return load(environment, adaptedBindings, mapper);
    }

    public static ToolCatalog load(AppEnvironment environment, List<ToolDeclaration<?>> declarations, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper, ExecutorService blockingExecutor) {
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        var adaptedBindings = new LinkedHashMap<String, ToolBinding<?>>();
        Objects.requireNonNull(bindings, "bindings").forEach((name, binding) -> adaptedBindings.put(name, binding.withBlockingExecutor(blockingExecutor)));
        return load(environment, declarations, adaptedBindings, mapper);
    }

    static ToolCatalog fromMetadata(AppEnvironment environment, McpJsonMapper mapper, ToolMetadata[] metadata, Iterable<Map.Entry<String, ToolBinding<?>>> bindings) {
        return fromDeclarations(environment, mapper, metadata, null, bindings);
    }

    private static ToolCatalog fromDeclarations(AppEnvironment environment, McpJsonMapper mapper, ToolMetadata[] metadata, List<ToolDeclaration<?>> declarations, Iterable<Map.Entry<String, ToolBinding<?>>> bindings) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(bindings, "bindings");
        if (declarations != null) {
            validateDeclarations(declarations);
        }

        Set<String> metadataNames = new java.util.HashSet<>();
        for (ToolMetadata tool : metadata) {
            if (tool == null) {
                throw new IllegalArgumentException("Malformed tool metadata entry");
            }
            String name = tool.name();
            if (!metadataNames.add(name)) {
                throw new IllegalArgumentException("Duplicate tool metadata: " + name);
            }
        }

        Map<String, ToolBinding<?>> boundBindings = collectBindings(bindings);
        for (String name : boundBindings.keySet()) {
            if (!metadataNames.contains(name)) {
                throw new IllegalArgumentException("Handler without tool metadata: " + name);
            }
        }
        if (declarations != null) {
            Set<String> declarationNames = new HashSet<>();
            for (ToolDeclaration<?> declaration : declarations) {
                declarationNames.add(declaration.name());
            }
            for (String name : declarationNames) {
                if (!metadataNames.contains(name)) {
                    throw new IllegalArgumentException("Typed tool declaration without metadata: " + name);
                }
                if (!boundBindings.containsKey(name)) {
                    throw new IllegalArgumentException("Missing tool handler: " + name);
                }
            }
        }

        var definitions = new ArrayList<ToolDefinition>();
        for (ToolMetadata tool : metadata) {
            String name = tool.name();
            ToolBinding<?> binding = boundBindings.get(name);
            if (binding == null) {
                throw new IllegalArgumentException("Missing tool handler: " + name);
            }
            ToolAvailability availability = ToolAvailability.ALWAYS;
            if (declarations != null) {
                ToolDeclaration<?> declaration = findDeclaration(declarations, name);
                if (declaration == null) {
                    throw new IllegalArgumentException("Missing typed tool declaration: " + name);
                }
                if (declaration.input() != binding.input()) {
                    throw new IllegalArgumentException("Tool binding input differs from its declaration: " + name);
                }
                availability = declaration.availability();
            }
            definitions.add(new ToolDefinition(name, tool.description(), binding, availability));
        }
        return new ToolCatalog(environment, Objects.requireNonNull(mapper, "mapper"), definitions, boundBindings);
    }

    private static void validateDeclarations(List<ToolDeclaration<?>> declarations) {
        Set<String> names = new HashSet<>();
        for (ToolDeclaration<?> declaration : declarations) {
            Objects.requireNonNull(declaration, "Tool declaration");
            if (!names.add(declaration.name())) {
                throw new IllegalArgumentException("Duplicate tool declaration: " + declaration.name());
            }
        }
    }

    private static ToolDeclaration<?> findDeclaration(List<ToolDeclaration<?>> declarations, String name) {
        return declarations.stream().filter(declaration -> declaration.name().equals(name)).findFirst().orElse(null);
    }

    private static Map<String, ToolBinding<?>> collectBindings(Iterable<Map.Entry<String, ToolBinding<?>>> bindings) {
        Map<String, ToolBinding<?>> collected = new LinkedHashMap<>();
        for (Map.Entry<String, ToolBinding<?>> entry : bindings) {
            String name = Objects.requireNonNull(entry.getKey(), "Tool binding name");
            ToolBinding<?> binding = Objects.requireNonNull(entry.getValue(), "Tool binding: " + name);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Tool binding name must not be blank");
            }
            if (collected.putIfAbsent(name, binding) != null) {
                throw new IllegalArgumentException("Duplicate tool handler: " + name);
            }
        }
        return Map.copyOf(collected);
    }

    public static String errorText(String name, Throwable exception) {
        Throwable current = Objects.requireNonNull(exception, "exception");
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return "Error executing " + name + ": " + (message == null ? current.toString() : message);
    }

    public List<ToolDefinition> enabledDefinitions() {
        return definitions.stream().filter(this::isEnabled).toList();
    }

    public List<ToolDefinition> definitions() {
        return definitions;
    }

    public ToolBinding<?> binding(String name) {
        return bindingsByName.get(Objects.requireNonNull(name, "name"));
    }

    public CompletionStage<? extends ToolResult<?>> dispatch(String name, Map<String, Object> arguments, Cancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        ToolDefinition definition = definitionsByName.get(name);
        if (definition == null || !isEnabled(definition)) {
            return ToolHandlers.completed(ToolResult.error("Unknown tool: " + name));
        }
        try {
            ToolBinding<?> binding = bindingsByName.get(name);
            if (binding == null) {
                return ToolHandlers.completed(ToolResult.error("Unknown tool: " + name));
            }
            return Objects.requireNonNull(binding.invoke(mapper, arguments == null ? Map.of() : JsonValues.freezeMap(arguments), cancellation), "Tool handler result: " + name);
        } catch (RuntimeException exception) {
            return ToolHandlers.completed(ToolResult.error(errorText(name, exception)));
        }
    }

    private boolean isEnabled(ToolDefinition definition) {
        return switch (definition.availability()) {
            case ALWAYS -> true;
            case SCRIPT_LOGS ->
                    environment.isTruthy("MCDEV_SCRIPT_LOGS") || environment.value("MCDEV_SESSION_LOG_DIR").filter(value -> !value.isBlank()).isPresent();
            case RUN_COMMAND -> environment.isTruthy("MCDEV_RUN_COMMAND");
        };
    }
}
