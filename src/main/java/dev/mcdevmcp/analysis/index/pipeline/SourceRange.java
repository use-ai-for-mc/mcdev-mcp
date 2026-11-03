package dev.mcdevmcp.analysis.index.pipeline;


value record SourceRange(int startOffset, int endOffset, int startLine, int endLine) {
    SourceRange {
        if (startOffset < 0 || endOffset < startOffset || startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Invalid source range: " + startOffset + ".." + endOffset + ", lines " + startLine + ".." + endLine);
        }
    }
}