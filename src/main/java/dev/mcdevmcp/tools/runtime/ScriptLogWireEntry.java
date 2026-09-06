package dev.mcdevmcp.tools.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

record ScriptLogWireEntry(String timestamp, boolean success, String code, boolean resultPresent, Object result, String output, String error, long duration_ms) {
    ScriptLogger.ScriptLogEntry toDomain() {
        return new ScriptLogger.ScriptLogEntry(Instant.parse(timestamp), success, code, resultPresent, result, output, error, Duration.ofMillis(duration_ms));
    }

    static ScriptLogWireEntry fromDomain(ScriptLogger.ScriptLogEntry entry) {
        return new ScriptLogWireEntry(entry.timestamp().toString(), entry.success(), entry.code(), entry.resultPresent(), entry.result(), entry.output(), entry.error(), entry.duration().toMillis());
    }

    static ScriptLogWireEntry fromJson(Map<?, ?> values) {
        Object duration = values.get("duration_ms");
        if (!(duration instanceof Number number)) {
            throw new IllegalArgumentException("Script log entry is missing duration_ms");
        }
        return new ScriptLogWireEntry(Objects.toString(values.get("timestamp")), Boolean.TRUE.equals(values.get("success")), Objects.toString(values.get("code")), values.containsKey("result"), values.get("result"), values.get("output") instanceof String output ? output : null, values.get("error") instanceof String error ? error : null, number.longValue());
    }
}