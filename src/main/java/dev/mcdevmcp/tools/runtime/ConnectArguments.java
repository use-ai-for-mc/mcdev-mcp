package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record ConnectArguments(@InputProperty(description = "WebSocket port. Default: scan 9876-9886", minimum = "1", maximum = "65535") Integer port, @InputProperty(description = "Disconnect and clear state before connecting (for switching instances)", defaultValue = "false") boolean reset) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static ConnectArguments fromJson(@JsonProperty("port") Integer port, @JsonProperty("reset") Boolean reset) {
        return new ConnectArguments(port, reset != null && reset);
    }
}
