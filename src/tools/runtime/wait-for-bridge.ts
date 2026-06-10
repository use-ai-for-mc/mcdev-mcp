import { bridgeSession } from "./session.js";
import {
    waitForBridge,
    BRIDGE_PORT_START,
    BRIDGE_PORT_END,
    DEFAULT_BRIDGE_WAIT_TIMEOUT_S,
} from "./session-control.js";

export const mcWaitForBridgeTool = {
    name: "mc_wait_for_bridge",
    description: `Block until a freshly (re)launched Minecraft client's
DebugBridge answers, then connect to it. Sweeps ports
${BRIDGE_PORT_START}-${BRIDGE_PORT_END} once per second, verifying the
answering instance is the expected one — by game directory when known from the
previous connection, else by Minecraft version — so a second running instance
(e.g. a 1.19 client next to the 1.21 one) is never mistaken for it. Read-only;
doesn't require session control.

Use this instead of polling mc_connect yourself after launching the client
(typically right after mc_quit_client + a detached launch command — see the
mcdev://guides/dev-loop resource). On timeout, the likely causes are a
launcher login prompt or a crash during startup.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            expectedVersion: {
                type: "string",
                description: "Only accept an instance reporting this Minecraft version (e.g. \"1.21.11\"). " +
                    "Overrides the identity remembered from the previous connection — use when " +
                    "deliberately switching instances.",
            },
            timeoutSeconds: {
                type: "number",
                description: `Give up after this many seconds. Default ${DEFAULT_BRIDGE_WAIT_TIMEOUT_S}.`,
            },
        },
        required: [],
    },

    handler: async (args: { expectedVersion?: string; timeoutSeconds?: number } = {}) => {
        const notes: string[] = [];
        try {
            // An explicit expectedVersion is a deliberate instance switch, so it
            // replaces (not supplements) the remembered identity — otherwise the
            // old gameDir would win the match and contradict the caller.
            const previous = bridgeSession.getSessionInfo();
            const expected = args.expectedVersion
                ? { version: args.expectedVersion }
                : { version: previous?.version, gameDir: previous?.gameDir };

            const timeoutMs = (args.timeoutSeconds ?? DEFAULT_BRIDGE_WAIT_TIMEOUT_S) * 1000;
            const found = await waitForBridge(expected, timeoutMs, (msg) => notes.push(msg));

            // Adopt (rather than connect(port)) so future auto-reconnects keep
            // scanning — the port can move again on the next relaunch.
            const info = await bridgeSession.adoptPort(found.port);
            const lines = [
                `Connected: Minecraft ${info.version} on port ${found.port}.`,
                info.gameDir ? `Game dir: ${info.gameDir}` : null,
                info.sessionControlEnabled !== undefined
                    ? `Session control: ${info.sessionControlEnabled ? "enabled" : "disabled"}`
                    : null,
                ...notes.map((n) => `Note: ${n}`),
            ].filter((l): l is string => l !== null);
            return { content: [{ type: "text" as const, text: lines.join("\n") }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            const noteText = notes.length > 0 ? `\n${notes.map((n) => `Note: ${n}`).join("\n")}` : "";
            return { content: [{ type: "text" as const, text: `${msg}${noteText}` }], isError: true };
        }
    }
};
