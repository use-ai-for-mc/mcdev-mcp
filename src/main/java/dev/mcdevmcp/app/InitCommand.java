package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "init", description = "Prepare sources and rebuild the analysis index")
@SuppressWarnings("unused")
public final class InitCommand implements Callable<Integer> {
    private final AnalysisOperations operations;

    @Option(names = {"-v", "--version"}, required = true, description = "Minecraft version")
    private String version;

    @Option(names = "--skip-callgraph", description = "Skip callgraph generation")
    private boolean skipCallgraph;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    public InitCommand(AnalysisOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public Integer call() {
        MinecraftVersion minecraft = new MinecraftVersion(MinecraftVersionValidator.requireSupported(version));
        var progress = CliProgressSink.forWriter(spec.commandLine().getOut());
        var sources = operations.prepareSources(minecraft, progress, Cancellation.none());
        var index = operations.rebuildIndex(minecraft, progress, Cancellation.none());
        spec.commandLine().getOut().printf("Prepared %d source root(s); indexed %d types.%n", sources.sourceRoots().size(), index.types());
        if (!skipCallgraph) {
            var callgraph = operations.rebuildCallgraph(minecraft, progress, Cancellation.none());
            spec.commandLine().getOut().printf("Recorded %d call edges.%n", callgraph.edges());
        }
        return 0;
    }
}