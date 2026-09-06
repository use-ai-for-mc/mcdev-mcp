package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record ScreenshotArguments(@InputProperty(description = "Integer downscale factor. 1 = full window resolution. 2 = half each axis (default).", minimum = "1", defaultValue = "2") int downscale, @InputProperty(description = "JPEG quality in [0.05, 1.0]. Default: 0.75.", minimum = "0.05", maximum = "1", defaultValue = "0.75") double quality) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static ScreenshotArguments fromJson(@JsonProperty("downscale") Integer downscale, @JsonProperty("quality") Double quality) {
        return new ScreenshotArguments(downscale == null ? 2 : downscale, quality == null ? 0.75 : quality);
    }

    ScreenshotArguments {
        if (downscale < 1) {
            throw new IllegalArgumentException("'downscale' must be at least 1");
        }
        if (!Double.isFinite(quality) || quality < 0.05 || quality > 1) {
            throw new IllegalArgumentException("'quality' must be finite and in [0.05, 1.0]");
        }
    }
}
