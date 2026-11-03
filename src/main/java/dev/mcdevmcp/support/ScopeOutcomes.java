package dev.mcdevmcp.support;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;

public final class ScopeOutcomes {
    private ScopeOutcomes() {
    }

    public static boolean timedOut(Throwable failure) {
        return failure instanceof ExecutionException execution && execution.getCause() instanceof StructuredTaskScope.CancelledByTimeoutException;
    }

    public static Throwable cause(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == null ? failure : cause;
    }
}
