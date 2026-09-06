package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record FindRefsArguments(@InputProperty(description = "Fully qualified class name (e.g., \"net.minecraft.client.MinecraftClient\")", required = true) String className, @InputProperty(description = "Method name to find references for", required = true) String methodName, @InputProperty(description = "callers = who calls this method, callees = what this method calls", required = true) ReferenceDirection direction, @InputProperty(description = "Optional: max results to return (default 100, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.", minimum = "1") Integer limit, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
