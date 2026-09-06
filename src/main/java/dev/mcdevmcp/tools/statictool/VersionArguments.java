package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record VersionArguments(@InputProperty(description = "Action to perform", required = true) VersionAction action, @InputProperty(description = "(set) Minecraft version to activate (e.g., \"1.21.11\")") MinecraftVersion version) {
}
