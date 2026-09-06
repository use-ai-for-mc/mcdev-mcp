package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * A deliberate Java/Node correction with mandatory human review rationale.
 */
public record ReviewedNodeDifference(ReviewedNodeDifferenceKind kind, String key, String explanation) {
    public ReviewedNodeDifference {
        Objects.requireNonNull(kind, "kind");
        key = requireText(key, "key");
        explanation = requireText(explanation, "explanation");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Reviewed Node difference " + name + " must not be blank");
        }
        return value;
    }
}
