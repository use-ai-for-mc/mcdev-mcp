package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

import java.time.Duration;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(value = RecordInterval.Frame.class, name = "frame"), @JsonSubTypes.Type(value = RecordInterval.Fixed.class, name = "fixed")})
sealed interface RecordInterval permits RecordInterval.Frame, RecordInterval.Fixed {

    record Frame() implements RecordInterval {
    }

    record Fixed(@InputProperty(required = true, minimum = "0.001") Duration intervalSeconds) implements RecordInterval {
        public Fixed {
            if (intervalSeconds == null || intervalSeconds.isZero() || intervalSeconds.isNegative()) {
                throw new IllegalArgumentException("intervalSeconds must be at least 0.001 seconds");
            }
            double intervalMillis = projectedMillis(intervalSeconds);
            if (!Double.isFinite(intervalMillis) || intervalMillis < 1.0) {
                throw new IllegalArgumentException("intervalSeconds must be at least 0.001 seconds");
            }
        }
    }

    static double projectedMillis(Duration duration) {
        return duration.getSeconds() * 1000.0 + duration.getNano() / 1_000_000.0;
    }
}
