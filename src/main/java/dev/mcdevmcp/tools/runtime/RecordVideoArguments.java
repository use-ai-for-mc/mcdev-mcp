package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

value record RecordVideoArguments(@InputProperty(description = "Number of frames to capture, 1..300. Required.", required = true, minimum = "1", maximum = "300") int frames, @InputProperty(description = "Use {kind:\"fixed\",intervalSeconds:0.05} for a fixed cadence, or {kind:\"frame\"} for every render tick (~60 Hz). Default {kind:\"frame\"}.") RecordInterval interval, @InputProperty(description = "\"grid\" (one composed JPEG, default) or \"frames\" (N separate JPEGs).", defaultValue = "grid") RecordVideoOutput output, @InputProperty(description = "Columns in grid layout. Default max(1, ceil(sqrt(frames))). Only used in \"grid\" mode.", minimum = "1") int gridCols, @InputProperty(description = "Integer downscale factor. Default 2 (half each axis).", minimum = "1", defaultValue = "2") int downscale, @InputProperty(description = "JPEG quality in [0.05, 1.0]. Default 0.75. In \"grid\" mode applies once to the composed image.", minimum = "0.05", maximum = "1", defaultValue = "0.75") double quality) {
    public RecordVideoArguments {
        if (frames < 1 || frames > 300) {
            throw new IllegalArgumentException("frames must be in [1, 300]");
        }
        if (interval == null) {
            throw new IllegalArgumentException("interval must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        if (gridCols < 1 || gridCols > frames) {
            throw new IllegalArgumentException("gridCols must be in [1, frames]");
        }
        if (downscale < 1) {
            throw new IllegalArgumentException("downscale must be at least 1");
        }
        if (!Double.isFinite(quality) || quality < 0.05 || quality > 1.0) {
            throw new IllegalArgumentException("quality must be finite and in [0.05, 1.0]");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    private static RecordVideoArguments fromJson(@JsonProperty(value = "frames", required = true) Integer frames, @JsonProperty("interval") RecordInterval interval, @JsonProperty("output") RecordVideoOutput output, @JsonProperty("gridCols") Integer gridCols, @JsonProperty("downscale") Integer downscale, @JsonProperty("quality") Double quality) {
        if (frames == null) {
            throw new IllegalArgumentException("frames must not be null");
        }
        int requiredFrames = frames;
        return new RecordVideoArguments(requiredFrames, interval == null ? new RecordInterval.Frame() : interval, output == null ? RecordVideoOutput.GRID : output, gridCols == null ? Math.max(1, (int) Math.ceil(Math.sqrt(requiredFrames))) : gridCols, downscale == null ? 2 : downscale, quality == null ? 0.75 : quality);
    }
}