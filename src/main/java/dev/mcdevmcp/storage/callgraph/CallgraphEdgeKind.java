package dev.mcdevmcp.storage.callgraph;

/**
 * What a callgraph edge records: a method calling another, or a method reading or writing a field.
 * A field edge's callee is the field's owner and name, and its descriptor is the field's type.
 */
public enum CallgraphEdgeKind {
    CALL, READ, WRITE
}
