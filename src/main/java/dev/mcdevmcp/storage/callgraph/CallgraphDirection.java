package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.model.MethodReference;

import java.util.Comparator;

/**
 * One sorted, indexed view of the edges of one kind: callers and readers and writers are looked up by
 * the method or field an edge reaches, callees by the method it starts in.
 */
enum CallgraphDirection {
    CALLERS(CallgraphArtifact.CALLERS_DATA, CallgraphArtifact.CALLERS_INDEX, CallgraphEdgeKind.CALL, true), CALLEES(CallgraphArtifact.CALLEES_DATA, CallgraphArtifact.CALLEES_INDEX, CallgraphEdgeKind.CALL, false), READERS(CallgraphArtifact.READERS_DATA, CallgraphArtifact.READERS_INDEX, CallgraphEdgeKind.READ, true), WRITERS(CallgraphArtifact.WRITERS_DATA, CallgraphArtifact.WRITERS_INDEX, CallgraphEdgeKind.WRITE, true);

    private static final Comparator<String> NULL_STRINGS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<Integer> NULL_INTEGERS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<CallgraphDataRecord> BY_CALLER = Comparator.comparing(CallgraphDataRecord::callerClass).thenComparing(CallgraphDataRecord::callerMethod).thenComparing(CallgraphDataRecord::callerDescriptor, NULL_STRINGS_FIRST).thenComparing(CallgraphDataRecord::lineNumber, NULL_INTEGERS_FIRST).thenComparingLong(CallgraphDataRecord::edgeId);
    private static final Comparator<CallgraphDataRecord> BY_CALLEE = Comparator.comparing(CallgraphDataRecord::calleeClass).thenComparing(CallgraphDataRecord::calleeMethod).thenComparing(CallgraphDataRecord::calleeDescriptor, NULL_STRINGS_FIRST).thenComparing(CallgraphDataRecord::lineNumber, NULL_INTEGERS_FIRST).thenComparingLong(CallgraphDataRecord::edgeId);
    private final CallgraphArtifact dataArtifact;
    private final CallgraphArtifact indexArtifact;
    private final CallgraphEdgeKind kind;
    private final boolean byCallee;

    CallgraphDirection(CallgraphArtifact dataArtifact, CallgraphArtifact indexArtifact, CallgraphEdgeKind kind, boolean byCallee) {
        this.dataArtifact = dataArtifact;
        this.indexArtifact = indexArtifact;
        this.kind = kind;
        this.byCallee = byCallee;
    }

    boolean holds(CallgraphDataRecord record) {
        return record.kind() == kind;
    }

    CallgraphEdgeKind kind() {
        return kind;
    }

    String lookupClass(CallgraphDataRecord record) {
        return byCallee ? record.calleeClass() : record.callerClass();
    }

    String lookupMethod(CallgraphDataRecord record) {
        return byCallee ? record.calleeMethod() : record.callerMethod();
    }

    MethodReference result(CallgraphDataRecord record) {
        return byCallee ? new MethodReference(record.callerClass(), record.callerMethod(), record.callerDescriptor(), record.lineNumber(), record.edgeId()) : new MethodReference(record.calleeClass(), record.calleeMethod(), record.calleeDescriptor(), record.lineNumber(), record.edgeId());
    }

    Comparator<CallgraphDataRecord> comparator() {
        return Comparator.comparing(this::lookupClass).thenComparing(this::lookupMethod).thenComparing(byCallee ? BY_CALLER : BY_CALLEE);
    }

    LookupKey lookupKey(CallgraphDataRecord record) {
        return new LookupKey(lookupClass(record), lookupMethod(record));
    }

    String dataFileName() {
        return dataArtifact.fileName();
    }

    String indexFileName() {
        return indexArtifact.fileName();
    }

    CallgraphArtifact dataArtifact() {
        return dataArtifact;
    }

    CallgraphArtifact indexArtifact() {
        return indexArtifact;
    }

    value record LookupKey(String className, String methodName) implements Comparable<LookupKey> {
        @Override
        public int compareTo(LookupKey other) {
            int classes = className.compareTo(other.className);
            return classes != 0 ? classes : methodName.compareTo(other.methodName);
        }
    }
}
