package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;

/**
 * Command-facing analysis operations, allowing deterministic CLI composition tests.
 */
public interface AnalysisOperations {
    PreparedSources prepareSources(MinecraftVersion version, ProgressSink progress, Cancellation cancellation);

    IndexSummary rebuildIndex(MinecraftVersion version, ProgressSink progress, Cancellation cancellation);

    CallgraphSummary rebuildCallgraph(MinecraftVersion version, ProgressSink progress, Cancellation cancellation);
}
