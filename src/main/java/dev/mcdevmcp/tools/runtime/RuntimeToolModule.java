package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeToolModule {
    private RuntimeToolModule() {
    }

    public static List<ToolDeclaration<?>> declarations() {
        var declarations = new ArrayList<ToolDeclaration<?>>();
        for (RuntimeBindingDeclaration<?> binding : bindingDeclarations()) {
            addDeclaration(declarations, binding);
        }
        return List.copyOf(declarations);
    }

    private static <A> void addDeclaration(List<ToolDeclaration<?>> declarations, RuntimeBindingDeclaration<A> binding) {
        declarations.add(binding.declaration());
    }

    public static List<RuntimeBindingDeclaration<?>> bindingDeclarations() {
        return List.of(new RuntimeBindingDeclaration<>(McConnectTool.DECLARATION, context -> McConnectTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McExecuteTool.DECLARATION, context -> McExecuteTool.handler(context.runtime(), context.scriptLogger(), context.scriptLogger() != null)), new RuntimeBindingDeclaration<>(McSnapshotTool.DECLARATION, context -> McSnapshotTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McNearbyEntitiesTool.DECLARATION, context -> McNearbyEntitiesTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McEntityDetailsTool.DECLARATION, context -> McEntityDetailsTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McNearbyBlocksTool.DECLARATION, context -> McNearbyBlocksTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McBlockDetailsTool.DECLARATION, context -> McBlockDetailsTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McLookedAtEntityTool.DECLARATION, context -> McLookedAtEntityTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McChatHistoryTool.DECLARATION, context -> McChatHistoryTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McScreenInspectTool.DECLARATION, context -> McScreenInspectTool.handler(context.runtime())), new RuntimeBindingDeclaration<>(McScreenshotTool.DECLARATION, context -> McScreenshotTool.handler(context.media())), new RuntimeBindingDeclaration<>(McRecordVideoTool.DECLARATION, context -> McRecordVideoTool.handler(context.media())), new RuntimeBindingDeclaration<>(McGetItemTextureTool.DECLARATION, context -> McGetItemTextureTool.handler(context.media())), new RuntimeBindingDeclaration<>(McGetEntityItemTextureTool.DECLARATION, context -> McGetEntityItemTextureTool.handler(context.media())), new RuntimeBindingDeclaration<>(McGetItemTextureByIdTool.DECLARATION, context -> McGetItemTextureByIdTool.handler(context.media())), new RuntimeBindingDeclaration<>(McSetEntityGlowTool.DECLARATION, context -> McSetEntityGlowTool.handler(context.media())), new RuntimeBindingDeclaration<>(McSetBlockGlowTool.DECLARATION, context -> McSetBlockGlowTool.handler(context.media())), new RuntimeBindingDeclaration<>(McClearBlockGlowTool.DECLARATION, context -> McClearBlockGlowTool.handler(context.media())), new RuntimeBindingDeclaration<>(McJoinServerTool.DECLARATION, context -> McJoinServerTool.handler(context.runtime(), context.sessionControl())), new RuntimeBindingDeclaration<>(McLeaveServerTool.DECLARATION, context -> McLeaveServerTool.handler(context.runtime(), context.sessionControl())), new RuntimeBindingDeclaration<>(McWaitUntilInWorldTool.DECLARATION, context -> McWaitUntilInWorldTool.handler(context.sessionControl())), new RuntimeBindingDeclaration<>(McQuitClientTool.DECLARATION, context -> McQuitClientTool.handler(context.sessionControl())), new RuntimeBindingDeclaration<>(McWaitForBridgeTool.DECLARATION, context -> McWaitForBridgeTool.handler(context.sessionControl())), new RuntimeBindingDeclaration<>(McScriptLogsTool.DECLARATION, context -> McScriptLogsTool.handler(context.scriptLogger())), new RuntimeBindingDeclaration<>(McRunCommandTool.DECLARATION, context -> McRunCommandTool.handler(context.runtime(), context.sessionControl())));
    }

    public static RuntimeContext context(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment) {
        return new RuntimeContext(session, mapper, environment);
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper) {
        return handlers(session, mapper, new AppEnvironment(Map.of()));
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment) {
        RuntimeContext context = context(session, mapper, environment);
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();
        for (RuntimeBindingDeclaration<?> declaration : bindingDeclarations()) {
            add(handlers, declaration.name(), declaration.create(context));
        }
        return handlers;
    }

    private static <A> void add(Map<String, ToolBinding<?>> handlers, String name, ToolBinding<A> binding) {
        if (handlers.putIfAbsent(name, binding) != null) {
            throw new IllegalStateException("Duplicate runtime tool binding: " + name);
        }
    }
}
