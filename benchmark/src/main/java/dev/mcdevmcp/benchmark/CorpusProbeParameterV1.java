package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.ParameterSymbol;

import java.util.Objects;

/**
 * Method parameter shape shared by the Java and frozen Node index projections.
 */
public record CorpusProbeParameterV1(String name, String type) {
    public CorpusProbeParameterV1 {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    static CorpusProbeParameterV1 from(ParameterSymbol value) {
        return new CorpusProbeParameterV1(value.name(), value.type());
    }
}
