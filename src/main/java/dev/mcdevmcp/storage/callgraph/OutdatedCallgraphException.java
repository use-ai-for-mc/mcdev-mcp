package dev.mcdevmcp.storage.callgraph;

import java.io.IOException;
import java.io.Serial;

/**
 * A callgraph bundle published by an earlier mcdev-mcp, in a schema this one no longer reads; scanning
 * the version again replaces it.
 */
final class OutdatedCallgraphException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    OutdatedCallgraphException(int schemaVersion) {
        super("Callgraph bundle uses schema " + schemaVersion + "; this mcdev-mcp reads schema " + CallgraphBundleLayout.SCHEMA_VERSION);
    }
}
