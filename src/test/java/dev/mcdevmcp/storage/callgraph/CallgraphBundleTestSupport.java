package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;

import java.nio.file.Path;
import java.util.List;

public final class CallgraphBundleTestSupport {
    private CallgraphBundleTestSupport() {
    }

    public static String publish(Path bundle, MinecraftVersion version, List<CallgraphDataRecord> records) throws Exception {
        return publish(bundle, version, records, 2048, 32);
    }

    static String publish(Path bundle, MinecraftVersion version, List<CallgraphDataRecord> records, int runRecords, int mergeFanIn) throws Exception {
        try (var writer = new CallgraphBundleWriter(bundle, version, "0".repeat(64), Cancellation.none(), runRecords, mergeFanIn)) {
            for (CallgraphDataRecord record : records) {
                writer.acceptLegacy(record);
            }
            return writer.publish(0, 0, records.size());
        }
    }
}
