package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "rebuild", description = "Rebuild the cached symbol index")
public final class RebuildCommand implements Callable<Integer> {
    private final AnalysisOperations operations;
    private final PlatformPaths paths;

    @Option(names = {"-v", "--version"}, required = true, description = "Minecraft version")
    private String version;

    @Option(names = "--with-callgraph", description = "Also rebuild the callgraph")
    private boolean withCallgraph;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    public RebuildCommand(AnalysisOperations operations, PlatformPaths paths) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public Integer call() {
        MinecraftVersion minecraft = new MinecraftVersion(MinecraftVersionValidator.requireSupported(version));
        Path source = paths.sourceRoot(minecraft);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            spec.commandLine().getErr().println("Source directory not found: " + source.toAbsolutePath().normalize());
            spec.commandLine().getErr().println("Run `init` first to download and decompile sources.");
            return 1;
        }
        spec.commandLine().getOut().printf("Rebuilding index for Minecraft %s...%n", minecraft.value());
        var progress = CliProgressSink.forWriter(spec.commandLine().getOut());
        var index = operations.rebuildIndex(minecraft, progress, Cancellation.none());
        spec.commandLine().getOut().printf("Indexed %d types.%n", index.types());
        if (withCallgraph) {
            var callgraph = operations.rebuildCallgraph(minecraft, progress, Cancellation.none());
            spec.commandLine().getOut().printf("Recorded %d call edges and %d field accesses.%n", callgraph.edges(), callgraph.fieldAccesses());
        }
        return 0;
    }
}