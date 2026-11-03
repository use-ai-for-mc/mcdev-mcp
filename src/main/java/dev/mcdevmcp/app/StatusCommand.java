package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.symbols.VersionStateRepository;
import dev.mcdevmcp.storage.migration.CachePathBoundary;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show cached analysis state")
public final class StatusCommand implements Callable<Integer> {
    private final PlatformPaths paths;
    private CachePathBoundary boundary;

    @Option(names = {"-v", "--version"}, description = "Minecraft version")
    private String version;

    @Spec
    private CommandSpec spec;

    public StatusCommand(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    private static String mark(boolean value) {
        return value ? "✓" : "✗";
    }

    /**
     * An index this release cannot read is not an index the tools can use, so it is marked absent with its remedy.
     */
    private static String indexMark(VersionState state, MinecraftVersion version) {
        return state == VersionState.NEEDS_REBUILD ? mark(false) + " (needs rebuild: run 'mcdev-mcp rebuild -v " + version.value() + "')" : mark(state == VersionState.READY);
    }

    @Override
    public Integer call() throws IOException {
        boundary = CachePathBoundary.open(paths);
        VersionStateRepository states = new VersionStateRepository(boundary.resolvedPaths());
        if (version != null) {
            printVersion(states, new MinecraftVersion(version));
            return 0;
        }

        List<MinecraftVersion> versions = new ArrayList<>();
        for (MinecraftVersion candidate : new CacheCleaner(boundary).cachedVersions()) {
            if (!MinecraftVersionValidator.isSupported(candidate.value())) {
                continue;
            }
            try (var lease = VersionOperationLease.read(boundary, candidate)) {
                lease.require(paths, candidate);
                if (hasSourceDirectory(candidate)) {
                    versions.add(candidate);
                }
            }
        }
        if (versions.isEmpty()) {
            spec.commandLine().getOut().println("Status: Not initialized");
            spec.commandLine().getOut().println("Run `mcdev-mcp init -v <version>` to set up.");
            return 0;
        }
        spec.commandLine().getOut().println("Cached Minecraft versions:");
        spec.commandLine().getOut().println();
        for (MinecraftVersion candidate : versions) {
            printCachedVersion(states, candidate);
        }
        spec.commandLine().getOut().printf("Total: %d version(s) cached%n", versions.size());
        return 0;
    }

    private boolean hasSourceDirectory(MinecraftVersion candidate) throws IOException {
        return Files.isDirectory(boundary.require(boundary.resolvedPaths().versionCache(candidate)), LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(boundary.require(boundary.resolvedPaths().sourceRoot(candidate)), LinkOption.NOFOLLOW_LINKS);
    }

    private void printVersion(VersionStateRepository states, MinecraftVersion value) throws IOException {
        try (var lease = VersionOperationLease.read(boundary, value)) {
            VersionState state = states.state(value, lease);
            boolean decompiled = Files.isDirectory(boundary.require(lease.resolvedPaths().sourceRoot(value)));
            boolean indexed = state == VersionState.READY;
            boolean hasCallgraph = graphStatus(value).equals("present");
            spec.commandLine().getOut().printf("%nMinecraft %s:%n", value.value());
            spec.commandLine().getOut().printf("  Decompiled: %s%n", mark(decompiled));
            spec.commandLine().getOut().printf("  Indexed: %s%n", indexMark(state, value));
            spec.commandLine().getOut().printf("  Callgraph: %s%n", mark(hasCallgraph));
            if (!decompiled && !indexed) {
                spec.commandLine().getOut().printf("%n  Run 'mcdev-mcp init -v %s' to initialize.%n", value.value());
            }
        }
    }

    private void printCachedVersion(VersionStateRepository states, MinecraftVersion value) throws IOException {
        try (var lease = VersionOperationLease.read(boundary, value)) {
            VersionState state = states.state(value, lease);
            spec.commandLine().getOut().printf("  %s:%n", value.value());
            spec.commandLine().getOut().printf("    Decompiled: %s%n", mark(hasSourceDirectory(value)));
            spec.commandLine().getOut().printf("    Indexed: %s%n", indexMark(state, value));
            spec.commandLine().getOut().printf("    Callgraph: %s%n", mark(graphStatus(value).equals("present")));
            spec.commandLine().getOut().println();
        }
    }

    private String graphStatus(MinecraftVersion value) throws IOException {
        return switch (CallgraphRepository.publicationStatus(boundary.require(boundary.resolvedPaths().callgraphBundle(value)))) {
            case ABSENT -> "absent";
            case PUBLISHED -> "present";
            case OUTDATED -> "outdated";
            case CORRUPT -> "corrupt";
        };
    }
}