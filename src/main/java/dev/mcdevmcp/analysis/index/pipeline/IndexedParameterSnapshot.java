package dev.mcdevmcp.analysis.index.pipeline;

record IndexedParameterSnapshot(long id, long methodId, int ordinal, String name, String type, boolean varargs, SourceRange range) {
}