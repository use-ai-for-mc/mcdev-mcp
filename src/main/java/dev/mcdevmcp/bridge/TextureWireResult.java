package dev.mcdevmcp.bridge;

import java.util.Objects;

/**
 * The provider representation of a PNG texture result.
 */
public record TextureWireResult(String base64Png, int width, int height, String spriteName) {
    public TextureWireResult {
        Objects.requireNonNull(base64Png, "base64Png");
        Objects.requireNonNull(spriteName, "spriteName");
    }
}