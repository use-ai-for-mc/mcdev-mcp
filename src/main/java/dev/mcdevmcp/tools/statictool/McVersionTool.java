package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.app.MinecraftVersionValidator;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.migration.CachePathBoundary;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import dev.mcdevmcp.support.AppVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;

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

    private static ContentToolResult<Void> set(StaticToolSupport support, VersionArguments arguments) throws IOException {
        if (arguments.version() == null) {
            return ToolResult.error("Error: 'version' is required for set action");
        }
        MinecraftVersion version = arguments.version();
        try (var lease = VersionOperationLease.read(support.paths(), version)) {
            if (!Files.isDirectory(lease.boundary().require(lease.resolvedPaths().sourceRoot(version)))) {
                return ToolResult.text("Version " + version.value() + " not initialized.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " init -v " + version.value() + "\n\n" + "This will download, decompile, and index Minecraft " + version.value() + " sources.");
            }
            if (support.state(version, lease) != VersionState.READY) {
                return ToolResult.text("Version " + version.value() + " not indexed.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " init -v " + version.value() + "\n\n" + "This will index Minecraft " + version.value() + " sources.");
            }
            support.activate(version);
            CallgraphRepository.PublicationStatus status = CallgraphRepository.publicationStatus(lease.boundary().require(lease.resolvedPaths().callgraphBundle(version)));
            if (status == CallgraphRepository.PublicationStatus.CORRUPT) {
                return ToolResult.text("Active version set to " + version.value() + ".\nIndexed: yes\nCallgraph: corrupt\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  " + AppVersion.commandLine() + " init -v " + version.value());
            }
            if (status == CallgraphRepository.PublicationStatus.OUTDATED) {
                return ToolResult.text("Active version set to " + version.value() + ".\nIndexed: yes\nCallgraph: needs rebuild\n\n" + "It was built by an earlier mcdev-mcp. STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " callgraph -v " + version.value());
            }
            String callgraph = status == CallgraphRepository.PublicationStatus.PUBLISHED ? "yes" : "no";
            return ToolResult.text("Active version set to " + version.value() + ".\nIndexed: yes\nCallgraph: " + callgraph);
        }
    }

    private static ContentToolResult<Void> list(StaticToolSupport support) throws IOException {
        CachePathBoundary boundary = CachePathBoundary.open(support.paths());
        var lines = new ArrayList<String>();
        for (MinecraftVersion version : new CacheCleaner(boundary).cachedVersions()) {
            if (!MinecraftVersionValidator.isSupported(version.value())) {
                continue;
            }
            try (var lease = VersionOperationLease.read(boundary, version)) {
                if (!isInitializedVersion(lease)) {
                    continue;
                }
                String decompiled = PathWalker.isDecompiled(lease.resolvedPaths().sourceRoot(version), boundary) ? "decompiled" : "not decompiled";
                String indexed = switch (support.state(version, lease)) {
                    case READY -> "indexed";
                    case NEEDS_REBUILD -> "index needs rebuild";
                    case SOURCE_ONLY, ABSENT -> "not indexed";
                };
                CallgraphRepository.PublicationStatus status = CallgraphRepository.publicationStatus(boundary.require(lease.resolvedPaths().callgraphBundle(version)));
                String callgraph = switch (status) {
                    case PUBLISHED -> "callgraph";
                    case ABSENT -> "no callgraph";
                    case OUTDATED -> "callgraph needs rebuild";
                    case CORRUPT -> "corrupt callgraph";
                };
                lines.add(version.value() + ": " + decompiled + ", " + indexed + ", " + callgraph);
            }
        }
        if (lines.isEmpty()) {
            return ToolResult.text("No Minecraft versions found.\n\nRun this command to initialize a version:\n  " + AppVersion.commandLine() + " init -v <version>\n\nExample:\n  " + AppVersion.commandLine() + " init -v 1.21.11");
        }
        String active = support.active().map(version -> "\n\nActive version: " + version.value()).orElse("\n\nNo active version set. Use mc_version with action=\"set\".");
        return ToolResult.text("Available Minecraft versions:\n" + String.join("\n", lines) + active);
    }

    private static boolean isInitializedVersion(VersionOperationLease lease) throws IOException {
        MinecraftVersion version = lease.version();
        return Files.isDirectory(lease.boundary().require(lease.resolvedPaths().versionCache(version)), LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(lease.boundary().require(lease.resolvedPaths().sourceRoot(version)), LinkOption.NOFOLLOW_LINKS);
    }
}