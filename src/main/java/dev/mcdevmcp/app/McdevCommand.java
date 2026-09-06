package dev.mcdevmcp.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "mcdev-mcp", mixinStandardHelpOptions = true, versionProvider = McdevVersionProvider.class, description = "Minecraft mod-development MCP server", subcommands = {ServeCommand.class, InitCommand.class, CallgraphCommand.class, RebuildCommand.class, StatusCommand.class, CleanCommand.class})
@SuppressWarnings("unused")
public final class McdevCommand implements Runnable {
    @Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}