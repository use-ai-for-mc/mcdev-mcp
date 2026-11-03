package dev.mcdevmcp.analysis.index.pipeline;

import javax.tools.SimpleJavaFileObject;

final class MemorySourceFileObject extends SimpleJavaFileObject {
    private final DecodedSource source;
    private final String binaryName;

    MemorySourceFileObject(DecodedSource source) {
        this(source, source.primaryBinaryName());
    }

    MemorySourceFileObject(DecodedSource source, String binaryName) {
        super(source.uri(), Kind.SOURCE);
        this.source = source;
        this.binaryName = binaryName;
    }

    DecodedSource source() {
        return source;
    }

    String binaryName() {
        return binaryName;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source.content();
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        int separator = binaryName.lastIndexOf('.');
        return kind == Kind.SOURCE && binaryName.substring(separator + 1).equals(simpleName);
    }
}
