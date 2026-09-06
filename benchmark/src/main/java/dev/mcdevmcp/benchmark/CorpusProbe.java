package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * A typed repository query and the collision-safe signature of its complete result.
 */
public record CorpusProbe(CorpusProbeKind kind, String key, String signature) {
    public CorpusProbe {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Corpus probe key must not be blank");
        }
        signature = CorpusExpectation.requireSha256(signature, "signature");
    }
}
