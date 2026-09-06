package dev.mcdevmcp.tools.runtime;

import java.util.Objects;

record TextureResult(String base64Png, int width, int height, String spriteName) {
    public TextureResult {
        Objects.requireNonNull(base64Png, "base64Png");
        Objects.requireNonNull(spriteName, "spriteName");
    }
}
