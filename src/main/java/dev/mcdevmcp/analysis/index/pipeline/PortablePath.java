package dev.mcdevmcp.analysis.index.pipeline;

import java.nio.file.Path;
import java.util.Objects;

record PortablePath(Path path) implements Comparable<PortablePath> {
    PortablePath {
        path = Objects.requireNonNull(path, "path").normalize();
    }

    String value() {
        StringBuilder value = new StringBuilder();
        for (Path part : path) {
            if (!value.isEmpty()) {
                value.append('/');
            }
            value.append(part);
        }
        return value.toString();
    }

    @Override
    public int compareTo(PortablePath other) {
        return value().compareTo(other.value());
    }
}