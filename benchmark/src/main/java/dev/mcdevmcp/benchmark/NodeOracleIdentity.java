package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * Git identity of the pinned, immutable Node parity oracle.
 */
public record NodeOracleIdentity(String commit, String treeHash) {
    public NodeOracleIdentity {
        commit = requireGitObjectId(commit, "commit");
        treeHash = requireGitObjectId(treeHash, "treeHash");
    }

    private static String requireGitObjectId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Node oracle " + name + " must be a lowercase Git object ID");
        }
        return value;
    }
}
