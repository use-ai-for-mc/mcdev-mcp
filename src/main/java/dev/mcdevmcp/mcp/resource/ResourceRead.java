package dev.mcdevmcp.mcp.resource;

import java.net.URI;

public value record ResourceRead(URI uri, String mimeType, String text) {
}