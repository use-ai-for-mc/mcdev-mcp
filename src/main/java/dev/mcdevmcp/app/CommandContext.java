package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.PlatformPaths;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Explicit command composition shared by the Picocli command instances.
 */
public value record CommandContext(AnalysisOperations operations, PlatformPaths paths) implements CommandLine.IFactory {
    public CommandContext {
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(paths, "paths");
    }

    public static CommandContext production() {
        PlatformPaths paths = PlatformPaths.forEnvironment(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home")));
        return new CommandContext(AnalysisPipeline.production(), paths);
    }

    @Override
    public <K> K create(Class<K> commandClass) throws Exception {
        Objects.requireNonNull(commandClass, "commandClass");
        if (commandClass == InitCommand.class) {
            return commandClass.cast(new InitCommand(operations));
        }
        if (commandClass == CallgraphCommand.class) {
            return commandClass.cast(new CallgraphCommand(operations, paths));
        }
        if (commandClass == RebuildCommand.class) {
            return commandClass.cast(new RebuildCommand(operations, paths));
        }
        if (commandClass == StatusCommand.class) {
            return commandClass.cast(new StatusCommand(paths));
        }
        if (commandClass == CleanCommand.class) {
            return commandClass.cast(new CleanCommand(paths));
        }
        return CommandLine.defaultFactory().create(commandClass);
    }
}