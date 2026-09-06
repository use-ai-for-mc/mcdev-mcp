package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record GetMethodArguments(@InputProperty(description = "Fully qualified class name (e.g., \"net.minecraft.client.MinecraftClient\")", required = true) String className, @InputProperty(description = "Method name (e.g., \"tick\", \"render\", \"onUse\")", required = true) String methodName, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
