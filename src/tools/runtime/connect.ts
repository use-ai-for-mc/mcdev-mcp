import { bridgeSession } from "./session.js";
import { SessionInfo } from "./types.js";

const DEFAULT_PORT = 9876;

function formatSessionInfo(info: Partial<SessionInfo>, port: number | null): string {
    const lines: string[] = [];
    if (info.version) lines.push(`Minecraft ${info.version}`);
    if (port) lines.push(`Port: ${port}`);
    if (info.gameDir) lines.push(`Game dir: ${info.gameDir}`);
    if (info.latestLog) lines.push(`Log: ${info.latestLog}`);
    if (info.mappingStatus) lines.push(`Mappings: ${info.mappingStatus}`);
    if (info.sessionControlEnabled !== undefined) {
        lines.push(`Session control: ${info.sessionControlEnabled ? "enabled" : "disabled"}`);
    }
    return lines.join("\n");
}

export const mcConnectTool = {
    name: "mc_connect",
    description: `Connect to a running Minecraft instance with the DebugBridge mod.
Optional - other runtime tools auto-connect if needed. Useful to specify a
non-default port, reconnect to a different instance, or get session info.

If port is not specified, scans ports ${DEFAULT_PORT}-${DEFAULT_PORT + 10} to find the mod.
Use reset=true to disconnect and clear state before reconnecting.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            port: {
                type: "number",
                description: `WebSocket port. Default: scan ${DEFAULT_PORT}-${DEFAULT_PORT + 10}`,
            },
            reset: {
                type: "boolean",
                description: "Disconnect and clear state before connecting (for switching instances)",
            },
        },
        required: [],
    },

    handler: async (args: { port?: number; reset?: boolean }) => {
        // Handle reset request
        if (args.reset) {
            bridgeSession.reset();
        }

        if (bridgeSession.isConnected && !args.reset && args.port === undefined) {
            const info = bridgeSession.getSessionInfo();
            const connectedPort = bridgeSession.getConnectedPort();
            return {
                content: [{
                    type: "text" as const,
                    text: `Already connected.\n${formatSessionInfo(info ?? {}, connectedPort)}\n\nUse reset=true to reconnect.`
                }]
            };
        }
        try {
            const info = await bridgeSession.connect(args.port);
            const connectedPort = bridgeSession.getConnectedPort();
            return {
                content: [{
                    type: "text" as const,
                    text: `Connected!\n${formatSessionInfo(info, connectedPort)}`
                }]
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            // Classify any "the mod isn't accepting connections" shape as
            // refused → suggest the user start Minecraft. We match:
            //   * `ECONNREFUSED` — kernel says "no listener" (the common case
            //     on localhost when the mod isn't running).
            //   * `Could not connect` — BridgeSession's own scan-failure
            //     message in `connectWithScan`.
            //   * `timed out connecting` — BridgeSession's per-port 2s
            //     timeout from `connectToPort`; ETIMEDOUT-style failures are
            //     rare on localhost but show up under firewalls.
            // Anything else stays "investigate" so we don't tell users to
            // "start Minecraft" for, say, an EHOSTUNREACH route issue.
            const refused = /ECONNREFUSED|Could not connect|timed out connecting/i.test(msg);
            const portsTried = args.port !== undefined
                ? [args.port]
                : Array.from({ length: 11 }, (_, i) => DEFAULT_PORT + i);
            const structured = {
                connected: false,
                action: refused ? "start_minecraft" : "investigate",
                ports_tried: portsTried,
                message: refused
                    ? "DebugBridge mod is not running on any scanned port. Ask the user to launch Minecraft with the DebugBridge mod loaded, then retry mc_connect."
                    : `Connection failed: ${msg}`,
                raw_error: msg,
            };
            return { content: [{ type: "text" as const, text: JSON.stringify(structured, null, 2) }], isError: true };
        }
    }
};
