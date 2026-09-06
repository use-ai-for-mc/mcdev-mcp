package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.model.MethodReference;

import java.util.Comparator;

enum CallgraphDirection {
    CALLERS(CallgraphArtifact.CALLERS_DATA, CallgraphArtifact.CALLERS_INDEX) {
        @Override
        String lookupClass(CallgraphDataRecord record) {
            return record.calleeClass();
        }

        @Override
        String lookupMethod(CallgraphDataRecord record) {
            return record.calleeMethod();
        }

        @Override
        MethodReference result(CallgraphDataRecord record) {
            return new MethodReference(record.callerClass(), record.callerMethod(), record.callerDescriptor(), record.lineNumber(), record.edgeId());
        }

        @Override
        Comparator<CallgraphDataRecord> resultComparator() {
            return Comparator.comparing(CallgraphDataRecord::callerClass).thenComparing(CallgraphDataRecord::callerMethod).thenComparing(CallgraphDataRecord::callerDescriptor, NULL_STRINGS_FIRST).thenComparing(CallgraphDataRecord::lineNumber, NULL_INTEGERS_FIRST).thenComparingLong(CallgraphDataRecord::edgeId);
        }
    }, CALLEES(CallgraphArtifact.CALLEES_DATA, CallgraphArtifact.CALLEES_INDEX) {
        @Override
        String lookupClass(CallgraphDataRecord record) {
            return record.callerClass();
        }

        @Override
        String lookupMethod(CallgraphDataRecord record) {
            return record.callerMethod();
        }

        @Override
        MethodReference result(CallgraphDataRecord record) {
            return new MethodReference(record.calleeClass(), record.calleeMethod(), record.calleeDescriptor(), record.lineNumber(), record.edgeId());
        }

        @Override
        Comparator<CallgraphDataRecord> resultComparator() {
            return Comparator.comparing(CallgraphDataRecord::calleeClass).thenComparing(CallgraphDataRecord::calleeMethod).thenComparing(CallgraphDataRecord::calleeDescriptor, NULL_STRINGS_FIRST).thenComparing(CallgraphDataRecord::lineNumber, NULL_INTEGERS_FIRST).thenComparingLong(CallgraphDataRecord::edgeId);
        }
    };

    private static final Comparator<String> NULL_STRINGS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<Integer> NULL_INTEGERS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private final CallgraphArtifact dataArtifact;
    private final CallgraphArtifact indexArtifact;

    CallgraphDirection(CallgraphArtifact dataArtifact, CallgraphArtifact indexArtifact) {
        this.dataArtifact = dataArtifact;
        this.indexArtifact = indexArtifact;
    }

    abstract String lookupClass(CallgraphDataRecord record);

    abstract String lookupMethod(CallgraphDataRecord record);

    abstract MethodReference result(CallgraphDataRecord record);

    abstract Comparator<CallgraphDataRecord> resultComparator();

    Comparator<CallgraphDataRecord> comparator() {
        return Comparator.comparing(this::lookupClass).thenComparing(this::lookupMethod).thenComparing(resultComparator());
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

    record LookupKey(String className, String methodName) implements Comparable<LookupKey> {
        @Override
        public int compareTo(LookupKey other) {
            int classes = className.compareTo(other.className);
            return classes != 0 ? classes : methodName.compareTo(other.methodName);
        }
    }
}
