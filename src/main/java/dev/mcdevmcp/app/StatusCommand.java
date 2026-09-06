package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.h2.VersionStateRepository;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show cached analysis state")
@SuppressWarnings("unused")
public final class StatusCommand implements Callable<Integer> {
    private final PlatformPaths paths;

    @Option(names = {"-v", "--version"}, description = "Minecraft version")
    private String version;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    public StatusCommand(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public Integer call() throws IOException {
        VersionStateRepository states = new VersionStateRepository(paths);
        if (version != null) {
            printVersion(states, new MinecraftVersion(version));
            return 0;
        }

        List<MinecraftVersion> versions = new CacheCleaner(paths).cachedVersions().stream().filter(candidate -> MinecraftVersionValidator.isSupported(candidate.value())).filter(this::hasSourceDirectory).toList();
        if (versions.isEmpty()) {
            spec.commandLine().getOut().println("Status: Not initialized");
            spec.commandLine().getOut().println("Run `mcdev-mcp init -v <version>` to set up.");
            return 0;
        }
        spec.commandLine().getOut().println("Cached Minecraft versions:");
        spec.commandLine().getOut().println();
        versions.forEach(candidate -> printCachedVersion(states, candidate));
        spec.commandLine().getOut().printf("Total: %d version(s) cached%n", versions.size());
        return 0;
    }

    private boolean hasSourceDirectory(MinecraftVersion candidate) {
        return Files.isDirectory(paths.versionCache(candidate), LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(paths.sourceRoot(candidate), LinkOption.NOFOLLOW_LINKS);
    }

    private void printVersion(VersionStateRepository states, MinecraftVersion value) {
        VersionState state = states.state(value);
        String graph = graphStatus(value);
        if (state == VersionState.NEEDS_REBUILD) {
            spec.commandLine().getOut().printf("%s: %s, callgraph %s%n", value.value(), state.name().toLowerCase(Locale.ROOT).replace('_', '-'), graph);
            return;
        }

        boolean decompiled = Files.isDirectory(paths.sourceRoot(value));
        boolean indexed = state == VersionState.READY;
        boolean hasCallgraph = graph.equals("present");
        spec.commandLine().getOut().printf("%nMinecraft %s:%n", value.value());
        spec.commandLine().getOut().printf("  Decompiled: %s%n", mark(decompiled));
        spec.commandLine().getOut().printf("  Indexed: %s%n", mark(indexed));
        spec.commandLine().getOut().printf("  Callgraph: %s%n", mark(hasCallgraph));
        if (!decompiled && !indexed) {
            spec.commandLine().getOut().printf("%n  Run 'mcdev-mcp init -v %s' to initialize.%n", value.value());
        }
    }

    private void printCachedVersion(VersionStateRepository states, MinecraftVersion value) {
        VersionState state = states.state(value);
        spec.commandLine().getOut().printf("  %s:%n", value.value());
        spec.commandLine().getOut().println("    Decompiled: ✓");
        spec.commandLine().getOut().printf("    Indexed: %s%n", mark(state == VersionState.READY || state == VersionState.NEEDS_REBUILD));
        spec.commandLine().getOut().printf("    Callgraph: %s%n", mark(graphStatus(value).equals("present")));
        spec.commandLine().getOut().println();
    }

    private String graphStatus(MinecraftVersion value) {
        return switch (CallgraphRepository.publicationStatus(paths.callgraphBundle(value))) {
            case ABSENT -> "absent";
            case PUBLISHED -> "present";
            case CORRUPT -> "corrupt";
        };
    }

    private static String mark(boolean value) {
        return value ? "✓" : "✗";
    }
}