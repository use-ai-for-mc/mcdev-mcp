package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "init", description = "Prepare sources and rebuild the analysis index")
public final class InitCommand implements Callable<Integer> {
    private final AnalysisOperations operations;

    @Option(names = {"-v", "--version"}, required = true, description = "Minecraft version")
    private String version;

    @Option(names = "--skip-callgraph", description = "Skip callgraph generation")
    private boolean skipCallgraph;

    @Option(names = "--refresh-sources", description = "Regenerate sources while retaining the original source and index")
    private boolean refreshSources;

    @Spec
    private CommandSpec spec;

    public InitCommand(AnalysisOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public Integer call() {
        MinecraftVersion minecraft = new MinecraftVersion(MinecraftVersionValidator.requireSupported(version));
        var progress = CliProgressSink.forWriter(spec.commandLine().getOut());
        var initialized = operations.initialize(minecraft, refreshSources ? SourceRefreshPolicy.EXPLICIT_REFRESH : SourceRefreshPolicy.NORMAL, progress, Cancellation.none());
        var sources = initialized.sources();
        var index = initialized.index();
        spec.commandLine().getOut().printf("Prepared %d source root(s); indexed %d types.%n", sources.sourceRoots().size(), index.types());
        initialized.retainedMigration().ifPresent(path -> spec.commandLine().getOut().printf("Retained original sources and index: %s%n", path));
        if (!skipCallgraph) {
            try {
                var callgraph = operations.rebuildCallgraph(minecraft, progress, Cancellation.none());
                spec.commandLine().getOut().printf("Recorded %d call edges and %d field accesses.%n", callgraph.edges(), callgraph.fieldAccesses());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Source/index committed; callgraph failed: " + failure.getMessage(), failure);
            }
        }
        return 0;
    }
}