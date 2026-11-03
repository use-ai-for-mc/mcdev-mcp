package dev.mcdevmcp.bridge;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The closed provider union for video recording. The {@code mode} value is
 * protocol metadata, not Java type metadata.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode")
@JsonSubTypes({@JsonSubTypes.Type(value = RecordVideoGridWireResult.class, name = "grid"), @JsonSubTypes.Type(value = RecordVideoFramesWireResult.class, name = "frames")})
public sealed interface RecordVideoWireResult permits RecordVideoGridWireResult, RecordVideoFramesWireResult {
}