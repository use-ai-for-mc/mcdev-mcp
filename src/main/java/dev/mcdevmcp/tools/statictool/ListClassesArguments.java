package dev.mcdevmcp.tools.statictool;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record ListClassesArguments(@JsonProperty("packagePath") @InputProperty(description = "Package path to list classes from (e.g., \"net.minecraft.client\", \"net.minecraft.world.entity\"). Matches exact package and all subpackages.", required = true) String packageName, @InputProperty(description = "Optional: max results to return (default 200, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.", minimum = "1") Integer limit, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
