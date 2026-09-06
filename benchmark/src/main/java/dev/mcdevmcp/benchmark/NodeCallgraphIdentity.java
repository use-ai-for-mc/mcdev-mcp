package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * Reviewed generator and storage contract for a frozen legacy Node callgraph database.
 */
public record NodeCallgraphIdentity(String generator, String generatorArtifactSha256, String protocol, String databaseSchema) {
    public static final String METHOD_CALL_PROTOCOL = "method-call-tab-v1";
    public static final String SQLITE_CALLS_SCHEMA = "sqlite-calls-v1";

    public NodeCallgraphIdentity {
        generator = requireNonBlank(generator, "generator");
        generatorArtifactSha256 = CorpusExpectation.requireSha256(generatorArtifactSha256, "generatorArtifactSha256");
        protocol = requireNonBlank(protocol, "protocol");
        databaseSchema = requireNonBlank(databaseSchema, "databaseSchema");
        requireSafeGeneratorIdentifier(generator);
        if (!METHOD_CALL_PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("Node callgraph protocol must be " + METHOD_CALL_PROTOCOL);
        }
        if (!SQLITE_CALLS_SCHEMA.equals(databaseSchema)) {
            throw new IllegalArgumentException("Node callgraph database schema must be " + SQLITE_CALLS_SCHEMA);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireSafeGeneratorIdentifier(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("generator must be an opaque safe identifier");
        }
    }
}
