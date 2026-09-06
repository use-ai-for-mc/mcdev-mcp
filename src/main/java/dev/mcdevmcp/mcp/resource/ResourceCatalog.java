package dev.mcdevmcp.mcp.resource;

import dev.mcdevmcp.support.JsonResourceReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ResourceCatalog {
    public static final String INSTRUCTIONS = String.join("\n", "mcdev-mcp gives AI coding agents two surfaces on a Minecraft codebase:", "static analysis of decompiled sources (mc_search, mc_get_class/method, mc_find_refs, …)", "and live runtime interaction via the DebugBridge mod (mc_execute, mc_snapshot, …).", "", "When you, as a coding agent, are about to write a Python script that talks", "to the Minecraft backend directly (i.e. opening the DebugBridge WebSocket", "yourself instead of using these MCP tools), first read the resource", "`mcdev://guides/python-scripting`. It documents the wire protocol, a minimal", "asyncio client, and the pitfalls — saves you reverse‑engineering the bridge", "from the tool implementations.", "", "When asked to test mod changes in the running game — rebuild, redeploy,", "restart the Minecraft client, rejoin a server — first read the resource", "`mcdev://guides/dev-loop`. It covers discovering the launcher/instance from", "the bridge's gameDir, deploying the jar, and driving mc_quit_client /", "mc_wait_for_bridge / mc_join_server, plus the failure playbook.");

    private final JsonResourceReader resourceReader;
    private final List<ResourceDefinition> definitions = List.of(new ResourceDefinition(URI.create("mcdev://guides/python-scripting"), "python-scripting", "Python client guide for the Minecraft DebugBridge", "How to drive a live Minecraft instance from Python by speaking the DebugBridge WebSocket protocol directly — wire format, a minimal asyncio client, and the Groovy surface you send through it. Read this before writing a Python script that bypasses the mcdev-mcp tools.", "text/markdown", "/guides/python-scripting.txt"), new ResourceDefinition(URI.create("mcdev://guides/dev-loop"), "dev-loop", "The mod dev loop: build → deploy → relaunch → rejoin", "How a coding agent runs the full mod test loop without human interaction: discover the launcher and instance from the bridge's gameDir, deploy the jar, quit via mc_quit_client, launch detached from the shell, reconnect with mc_wait_for_bridge, and rejoin a server. Read this before restarting the Minecraft client or automating mod testing.", "text/markdown", "/guides/dev-loop.txt"));
    private final Map<URI, ResourceDefinition> definitionsByUri = definitions.stream().collect(Collectors.toUnmodifiableMap(ResourceDefinition::uri, definition -> definition));

    public ResourceCatalog() {
        this(new JsonResourceReader(McpJsonDefaults.getMapper()));
    }

    ResourceCatalog(JsonResourceReader resourceReader) {
        this.resourceReader = Objects.requireNonNull(resourceReader, "resourceReader");
    }

    public static ResourceCatalog withMapper(McpJsonMapper mapper) {
        return new ResourceCatalog(new JsonResourceReader(mapper));
    }

    private static String canonicalLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public List<ResourceDefinition> definitions() {
        return definitions;
    }

    public ResourceRead read(URI uri) {
        ResourceDefinition definition = definitionsByUri.get(uri);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown resource URI: " + uri);
        }
        return new ResourceRead(definition.uri(), definition.mimeType(), canonicalLineEndings(resourceReader.readText(definition.classpathResource())));
    }
}