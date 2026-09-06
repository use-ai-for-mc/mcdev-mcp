package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.SourceRoot;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

record DecodedSource(SourceRoot root, Path absolutePath, Path relativePath, String relativeName, String content, URI uri, String packageName, List<String> topLevelNames) implements Comparable<DecodedSource> {
    DecodedSource {
        Objects.requireNonNull(root, "root");
        absolutePath = Objects.requireNonNull(absolutePath, "absolutePath").toAbsolutePath().normalize();
        relativePath = Objects.requireNonNull(relativePath, "relativePath").normalize();
        Objects.requireNonNull(relativeName, "relativeName");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(packageName, "packageName");
        topLevelNames = List.copyOf(topLevelNames);
    }

    DecodedSource withDeclarations(String parsedPackageName, List<String> parsedTopLevelNames) {
        return new DecodedSource(root, absolutePath, relativePath, relativeName, content, uri, parsedPackageName, parsedTopLevelNames);
    }

    String primaryBinaryName() {
        String fileName = relativePath.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    @Override
    public int compareTo(DecodedSource other) {
        int rootOrder = root.compareTo(other.root);
        return rootOrder != 0 ? rootOrder : relativeName.compareTo(other.relativeName);
    }
}