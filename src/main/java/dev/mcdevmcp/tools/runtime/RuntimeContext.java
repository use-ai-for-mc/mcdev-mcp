package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The typed, inert-to-construct runtime dependencies shared by runtime bindings.
 */
public final class RuntimeContext {
    private final RuntimeToolSupport runtime;
    private final SessionControlSupport sessionControl;
    private final MediaToolSupport media;
    private final ScriptLogger scriptLogger;

    RuntimeContext(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment, ScheduledExecutorService scheduler) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(scheduler, "scheduler");
        runtime = new RuntimeToolSupport(session, mapper);
        Optional<Path> sessionLogDirectory = environment.value("MCDEV_SESSION_LOG_DIR").filter(value -> !value.isBlank()).map(Path::of).or(() -> environment.isTruthy("MCDEV_SCRIPT_LOGS") ? Optional.of(ScriptLogger.dataDirectory(System.getProperty("os.name"), environment, Path.of(System.getProperty("user.home")))) : Optional.empty());
        scriptLogger = sessionLogDirectory.map(directory -> new ScriptLogger(directory, mapper, System.err::println)).orElse(null);
        sessionControl = new SessionControlSupport(session, environment, scheduler);
        media = new MediaToolSupport(runtime);
    }

    RuntimeToolSupport runtime() {
        return runtime;
    }

    SessionControlSupport sessionControl() {
        return sessionControl;
    }

    MediaToolSupport media() {
        return media;
    }

    ScriptLogger scriptLogger() {
        return scriptLogger;
    }
}