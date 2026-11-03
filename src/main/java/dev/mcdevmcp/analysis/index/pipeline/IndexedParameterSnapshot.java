package dev.mcdevmcp.analysis.index.pipeline;

value record IndexedParameterSnapshot(long id, long methodId, int ordinal, String name, String type, boolean varargs, SourceRange range) {
}