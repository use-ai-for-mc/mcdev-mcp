package dev.mcdevmcp.analysis.index.pipeline;

import java.util.Arrays;
import java.util.Objects;

record CompilerClassFile(String binaryName, String packageName, byte[] bytes) {
    CompilerClassFile {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(packageName, "packageName");
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}