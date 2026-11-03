package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

value record RunCommandArguments(@InputProperty(description = "The command to run", required = true) String command) {
    RunCommandArguments {
        command = RuntimeToolSupport.requiredString(command, "command");
    }
}
