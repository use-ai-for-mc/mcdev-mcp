import { bridgeSession } from "./session.js";
import {
    checkSessionControlEnabled,
    waitForPortClosed,
    DEFAULT_QUIT_TIMEOUT_S,
} from "./session-control.js";

export const mcQuitClientTool = {
    name: "mc_quit_client",
    description: `Gracefully shut down the entire Minecraft client. This tears
down the user's whole play session, including any world or server they're in —
only do it when asked, or as part of a dev loop the user set up (typically the
quit step of build → deploy → quit → launch → mc_wait_for_bridge; see the
mcdev://guides/dev-loop resource).

Fire-and-acknowledge: the WebSocket is expected to drop moments after the ack
(that counts as success). By default this then polls until the bridge port
stops listening, so a "done" result means the process is actually gone and
it's safe to relaunch. Requires session_control_enabled=true in the
DebugBridge config.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            waitForExit: {
                type: "boolean",
                description: "Poll until the bridge port stops listening before returning. Default true.",
            },
            timeoutSeconds: {
                type: "number",
                description: `How long to wait for the port to close. Default ${DEFAULT_QUIT_TIMEOUT_S}.`,
            },
        },
        required: [],
    },

    handler: async (args: { waitForExit?: boolean; timeoutSeconds?: number } = {}) => {
        try {
            const disabled = await checkSessionControlEnabled();
            if (disabled) {
                return { content: [{ type: "text" as const, text: disabled }], isError: true };
            }

            // Capture before quitting: the close handler nulls the port.
            const port = bridgeSession.getConnectedPort();

            try {
                const resp = await bridgeSession.send("quit", {});
                if (!resp.success) {
                    // Self-describing bridge error (e.g. session control gated) — pass through.
                    return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
                }
            } catch (e) {
                const msg = e instanceof Error ? e.message : String(e);
                if (!/connection closed/i.test(msg)) throw e;
                // Socket dropped before the ack — the client is shutting down.
            }
            bridgeSession.disconnect();

            if (args.waitForExit === false || port === null) {
                return {
                    content: [{
                        type: "text" as const,
                        text: `Quit queued — the client is shutting down. ` +
                            `Use mc_wait_for_bridge after relaunching to reconnect.`,
                    }],
                };
            }

            const timeoutS = args.timeoutSeconds ?? DEFAULT_QUIT_TIMEOUT_S;
            const closed = await waitForPortClosed(port, timeoutS * 1000);
            if (!closed) {
                return {
                    content: [{
                        type: "text" as const,
                        text: `Quit was acknowledged but port ${port} is still listening after ${timeoutS}s. ` +
                            `The game may be stuck on a save/exit prompt — ask the user to close it ` +
                            `manually before relaunching.`,
                    }],
                    isError: true,
                };
            }
            return {
                content: [{
                    type: "text" as const,
                    text: `Client shut down — port ${port} closed. Safe to relaunch; ` +
                        `use mc_wait_for_bridge to reconnect afterwards.`,
                }],
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
