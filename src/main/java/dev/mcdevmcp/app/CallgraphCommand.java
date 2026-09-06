package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "callgraph", description = "Rebuild the JSONL callgraph from prepared sources")
public final class CallgraphCommand implements Callable<Integer> {
    private final AnalysisOperations operations;
    private final PlatformPaths paths;

    @Option(names = {"-v", "--version"}, required = true, description = "Minecraft version")
    @SuppressWarnings("unused") // Assigned by picocli.
    private String version;

    @Spec
    @SuppressWarnings("unused") // Assigned by picocli.
    private picocli.CommandLine.Model.CommandSpec spec;

    public CallgraphCommand(AnalysisOperations operations, PlatformPaths paths) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public Integer call() {
        MinecraftVersion minecraft = new MinecraftVersion(MinecraftVersionValidator.requireSupported(version));
        spec.commandLine().getOut().printf("Generating callgraph for Minecraft %s...%n", minecraft.value());
        if (!Files.isDirectory(paths.sourceRoot(minecraft))) {
            throw new IllegalStateException("Minecraft %s not decompiled. Run 'init -v %s' first.".formatted(minecraft.value(), minecraft.value()));
        }
        if (!Files.isRegularFile(paths.symbolDatabase(minecraft))) {
            throw new IllegalStateException("Minecraft %s not indexed. Run 'init -v %s' first.".formatted(minecraft.value(), minecraft.value()));
        }
        var summary = operations.rebuildCallgraph(minecraft, CliProgressSink.forWriter(spec.commandLine().getOut()), Cancellation.none());
        spec.commandLine().getOut().printf("Recorded %d call edges.%n", summary.edges());
        return 0;
    }
}
