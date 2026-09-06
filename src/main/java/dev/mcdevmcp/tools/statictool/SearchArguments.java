package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record SearchArguments(@InputProperty(description = "The search query - class, method, or field name (or partial name)", required = true) String query, @InputProperty(description = "Optional: filter by type (class, method, or field)") SearchType type, @InputProperty(description = "Optional: max results to return (default 50, ceiling 1000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.", minimum = "1") Integer limit, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
