package dev.mcdevmcp.mcp.resource;

import java.net.URI;
import java.util.Objects;

public record ResourceDefinition(URI uri, String name, String title, String description, String mimeType, String classpathResource) {
    public ResourceDefinition {
        Objects.requireNonNull(uri, "Resource URI");
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Resource URI must be absolute: " + uri);
        }
        name = requireText(name, "Resource name");
        title = requireText(title, "Resource title");
        description = requireText(description, "Resource description");
        mimeType = requireText(mimeType, "Resource MIME type");
        classpathResource = requireText(classpathResource, "Resource classpath location");
        if (!classpathResource.startsWith("/")) {
            throw new IllegalArgumentException("Resource classpath location must be absolute: " + classpathResource);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}