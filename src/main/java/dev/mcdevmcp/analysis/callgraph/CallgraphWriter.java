package dev.mcdevmcp.analysis.callgraph;

import dev.mcdevmcp.storage.bundle.BundleHashes;
import dev.mcdevmcp.storage.callgraph.CallgraphBundleWriter;
import dev.mcdevmcp.storage.callgraph.CallgraphDataRecord;

import java.util.Objects;

final class CallgraphWriter {
    private final PublicationHook beforePublication;

    CallgraphWriter() {
        this(() -> {
        });
    }

    CallgraphWriter(PublicationHook beforePublication) {
        this.beforePublication = Objects.requireNonNull(beforePublication, "beforePublication");
    }

    Counts write(CallgraphRequest request, BatchSource source) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        String remappedJarSha256 = BundleHashes.sha256(request.remappedJar(), request.cancellation());
        try (var bundle = new CallgraphBundleWriter(request.outputBundle(), request.minecraftVersion(), remappedJarSha256, request.cancellation())) {
            long nextEdgeId = 1;
            int classes = 0;
            int methods = 0;
            InvocationExtractor.Extraction extraction;
            while ((extraction = source.next()) != null) {
                request.cancellation().throwIfCancelled();
                classes = Math.addExact(classes, 1);
                methods = Math.addExact(methods, extraction.methodCount());
                long firstEdgeId = nextEdgeId;
                long expectedLocalOrder = 0;
                for (CallEdge edge : extraction.edges()) {
                    request.cancellation().throwIfCancelled();
                    if (edge.encounterOrder() != expectedLocalOrder) {
                        throw new IllegalArgumentException("Non-sequential encounter order for " + extraction.className() + ": expected " + expectedLocalOrder + ", found " + edge.encounterOrder());
                    }
                    long edgeId = Math.addExact(firstEdgeId, edge.encounterOrder());
                    bundle.accept(new CallgraphDataRecord(edgeId, edge.callerClass(), edge.callerMethod(), edge.callerDescriptor(), edge.calleeClass(), edge.calleeMethod(), edge.calleeDescriptor(), edge.lineNumber()));
                    expectedLocalOrder++;
                }
                nextEdgeId = Math.addExact(firstEdgeId, expectedLocalOrder);
            }
            Counts counts = new Counts(classes, methods, nextEdgeId - 1);
            beforePublication.run();
            bundle.publish(counts.classes(), counts.methods(), counts.edges());
            return counts;
        }
    }

    interface BatchSource {
        InvocationExtractor.Extraction next() throws Exception;
    }

    @FunctionalInterface
    interface PublicationHook {
        void run() throws Exception;
    }

    record Counts(int classes, int methods, long edges) {
    }
}