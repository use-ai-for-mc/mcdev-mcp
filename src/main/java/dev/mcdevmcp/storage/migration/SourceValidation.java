package dev.mcdevmcp.storage.migration;

import java.util.List;
import java.util.Objects;

public value record SourceValidation(SourceValidationStatus status, List<String> diagnostics, List<String> requiredUnits, List<String> parsedUnits) {
    public SourceValidation {
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(diagnostics);
        requiredUnits = List.copyOf(requiredUnits);
        parsedUnits = List.copyOf(parsedUnits);
        if (status == SourceValidationStatus.VALID && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Valid source validation cannot contain failures");
        }
    }

    public boolean valid() {
        return status == SourceValidationStatus.VALID;
    }
}