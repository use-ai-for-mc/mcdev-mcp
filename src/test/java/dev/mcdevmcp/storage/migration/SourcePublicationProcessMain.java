package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public final class SourcePublicationProcessMain {
    private SourcePublicationProcessMain() {
    }

    static void main(String[] args) throws Exception {
        PlatformPaths paths = new PlatformPaths(Path.of(args[0]));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Path transaction = Path.of(args[1]);
        String stoppingPoint = args[2];
        var moves = new AtomicInteger();
        var publisher = new SourceIndexTransactionPublisher((event, _) -> {
            if (event.equals(stoppingPoint) || event.equals("AFTER_MOVE") && ("MOVE_" + moves.incrementAndGet()).equals(stoppingPoint)) {
                Runtime.getRuntime().halt(73);
            }
        });
        try (var lease = VersionOperationLease.write(paths, version)) {
            if (stoppingPoint.equals("RECOVER")) {
                publisher.recover(paths, version, lease);
            }
            else {
                SourceIndexSnapshot before = publisher.captureBefore(paths, version, lease, Cancellation.none());
                publisher.publish(paths, version, lease, transaction, transaction.resolve("candidate/client"), transaction.resolve("candidate/symbols.jsonl"), transaction.resolve("candidate/source-preparation.json"), before, Cancellation.none());
            }
        }
    }
}
