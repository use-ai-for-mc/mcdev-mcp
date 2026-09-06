package dev.mcdevmcp.tools.runtime;

/**
 * Closed domain union after provider-wire decoding and projection.
 */
public sealed interface RecordVideoResult permits RecordVideoGridResult, RecordVideoFramesResult {
    java.time.Duration captureDuration();

    java.time.Duration intervalDuration();
}