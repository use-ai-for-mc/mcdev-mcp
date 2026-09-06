package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record FindHierarchyArguments(@InputProperty(description = "Fully qualified class or interface name (e.g., \"net.minecraft.world.entity.Entity\", \"net.minecraft.world.item.Item\")", required = true) String className, @InputProperty(description = "subclasses = classes that extend this class, implementors = classes that implement this interface", required = true) HierarchyDirection direction, @InputProperty(description = "Optional: max results to return (default 200, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.", minimum = "1") Integer limit, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
