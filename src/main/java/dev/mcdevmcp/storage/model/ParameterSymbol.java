package dev.mcdevmcp.storage.model;

public record ParameterSymbol(long id, long methodId, int ordinal, String name, String type, boolean varargs, int startOffset, int endOffset, int startLine, int endLine) {
}