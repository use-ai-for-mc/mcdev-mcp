package dev.mcdevmcp.mcp.resource;

import java.net.URI;

public record ResourceRead(URI uri, String mimeType, String text) {
}