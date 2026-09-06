package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.app.MinecraftVersionValidator;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppVersion;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.stream.Collectors;

final class McVersionTool {
    static final ToolDeclaration<VersionArguments> DECLARATION = ToolDeclaration.of("mc_version", VersionArguments.class);

    private McVersionTool() {
    }

    static ToolBinding<VersionArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_version", () -> {
            if (arguments.action() == VersionAction.set) {
                return set(support, arguments);
            }
            if (arguments.action() == VersionAction.list) {
                return list(support);
            }
            throw new IllegalStateException("Unknown action: " + arguments.action());
        }));
    }

    private static ContentToolResult<Void> set(StaticToolSupport support, VersionArguments arguments) {
        if (arguments.version() == null) {
            return ToolResult.error("Error: 'version' is required for set action");
        }
        MinecraftVersion version = arguments.version();
        if (!Files.isDirectory(support.paths().sourceRoot(version))) {
            return ToolResult.text("Version " + version.value() + " not initialized.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " init -v " + version.value() + "\n\n" + "This will download, decompile, and index Minecraft " + version.value() + " sources.");
        }
        if (!support.indexed(version)) {
            return ToolResult.text("Version " + version.value() + " not indexed.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " init -v " + version.value() + "\n\n" + "This will index Minecraft " + version.value() + " sources.");
        }
        support.activate(version);
        CallgraphRepository.PublicationStatus status = CallgraphRepository.publicationStatus(support.paths().callgraphBundle(version));
        if (status == CallgraphRepository.PublicationStatus.CORRUPT) {
            return ToolResult.text("Active version set to " + version.value() + ".\nIndexed: yes\nCallgraph: corrupt\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v " + version.value());
        }
        String callgraph = status == CallgraphRepository.PublicationStatus.PUBLISHED ? "yes" : "no";
        return ToolResult.text("Active version set to " + version.value() + ".\nIndexed: yes\nCallgraph: " + callgraph);
    }

    private static ContentToolResult<Void> list(StaticToolSupport support) {
        var versions = new ArrayList<String>();
        PathWalker.listDirectories(support.paths().cacheRoot().resolve("cache"), versions);
        versions.removeIf(value -> !isInitializedVersion(support, value));
        if (versions.isEmpty()) {
            return ToolResult.text("No Minecraft versions found.\n\nRun this command to initialize a version:\n  java -jar " + AppVersion.executableJarName() + " init -v <version>\n\nExample:\n  java -jar " + AppVersion.executableJarName() + " init -v 1.21.11");
        }
        versions.sort(String::compareTo);
        String lines = versions.stream().map(value -> {
            MinecraftVersion version = new MinecraftVersion(value);
            String decompiled = PathWalker.isDecompiled(support.paths().sourceRoot(version)) ? "decompiled" : "not decompiled";
            String indexed = support.indexed(version) ? "indexed" : "not indexed";
            CallgraphRepository.PublicationStatus status = CallgraphRepository.publicationStatus(support.paths().callgraphBundle(version));
            String callgraph = switch (status) {
                case PUBLISHED -> "callgraph";
                case ABSENT -> "no callgraph";
                case CORRUPT -> "corrupt callgraph";
            };
            return value + ": " + decompiled + ", " + indexed + ", " + callgraph;
        }).collect(Collectors.joining("\n"));
        String active = support.active().map(version -> "\n\nActive version: " + version.value()).orElse("\n\nNo active version set. Use mc_version with action=\"set\".");
        return ToolResult.text("Available Minecraft versions:\n" + lines + active);
    }

    private static boolean isInitializedVersion(StaticToolSupport support, String value) {
        if (!MinecraftVersionValidator.isSupported(value)) {
            return false;
        }
        try {
            MinecraftVersion version = new MinecraftVersion(value);
            return Files.isDirectory(support.paths().versionCache(version), LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(support.paths().sourceRoot(version), LinkOption.NOFOLLOW_LINKS);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
