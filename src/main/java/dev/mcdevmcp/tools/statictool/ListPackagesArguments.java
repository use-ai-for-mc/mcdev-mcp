package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

record ListPackagesArguments(@InputProperty(description = "Optional: filter by namespace (minecraft or fabric)") SourceNamespace namespace, @InputProperty(description = "Optional: max results to return (default 500, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.", minimum = "1") Integer limit, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
}
