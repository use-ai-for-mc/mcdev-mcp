package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.tools.runtime.RuntimeToolModule;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CompleteToolBindings {
    private CompleteToolBindings() {
    }

    public static Map<String, ToolBinding<?>> including(McpJsonMapper mapper, Map<String, ToolBinding<?>> selectedBindings) {
        AppEnvironment environment = new AppEnvironment(Map.of());
        var bindings = new LinkedHashMap<String, ToolBinding<?>>();
        StaticToolModule.handlers(PlatformPaths.forEnvironment("Linux", environment.values(), Path.of(System.getProperty("user.home")))).forEach((name, binding) -> add(bindings, name, binding));
        try (var bridge = new BridgeTestHarness(mapper, environment, (_, _) -> new CompletableFuture<>())) {
            RuntimeToolModule.handlers(bridge.session(), mapper, environment).forEach((name, binding) -> add(bindings, name, binding));
        }
        selectedBindings.forEach((name, binding) -> {
            if (ToolDeclarations.all().stream().noneMatch(declaration -> declaration.name().equals(name))) {
                throw new IllegalArgumentException("Handler without tool metadata: " + name);
            }
            bindings.put(name, Objects.requireNonNull(binding, "Tool binding: " + name));
        });
        return Map.copyOf(bindings);
    }

    private static void add(Map<String, ToolBinding<?>> bindings, String name, ToolBinding<?> binding) {
        if (bindings.putIfAbsent(name, binding) != null) {
            throw new IllegalArgumentException("Duplicate production tool binding: " + name);
        }
    }
}