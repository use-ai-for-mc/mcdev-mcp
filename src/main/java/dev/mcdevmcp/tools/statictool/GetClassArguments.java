package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

record GetClassArguments(@InputProperty(description = "Fully qualified class name (e.g., \"net.minecraft.client.MinecraftClient\")", required = true) String className, @InputProperty(description = "How much to return. Default \"summary\".") ClassView view, @InputProperty(description = "Optional: Minecraft version to use (e.g., \"1.21.1\"). If not provided, uses the active version set by mc_version.") MinecraftVersion version) {
    GetClassArguments {
        view = view == null ? ClassView.summary : view;
    }
}
