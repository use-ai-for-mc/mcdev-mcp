package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

value record GetMethodArguments(@InputProperty(description = "Fully qualified class name (e.g., \"net.minecraft.client.MinecraftClient\")", required = true) String className, @InputProperty(description = "Method name (e.g., \"tick\", \"render\", \"onUse\")", required = true) String methodName, @InputProperty(description = "Optional: first line to return, numbered as in the method's \"// Lines:\" header. With fromLine or toLine, only the method's lines in that range are returned, without surrounding context; use it to read a long method in parts.", minimum = "1") Integer fromLine, @InputProperty(description = "Optional: last line to return, numbered as in the method's \"// Lines:\" header.", minimum = "1") Integer toLine, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
