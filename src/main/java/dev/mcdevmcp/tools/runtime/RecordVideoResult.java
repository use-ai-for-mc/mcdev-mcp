package dev.mcdevmcp.tools.runtime;

import java.time.Duration;

/**
 * Closed domain union after provider-wire decoding and projection.
 */
public sealed interface RecordVideoResult permits RecordVideoGridResult, RecordVideoFramesResult {
    Duration captureDuration();

    Duration intervalDuration();
}
