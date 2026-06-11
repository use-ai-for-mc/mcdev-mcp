import { bridgeSession } from "./session.js";
import { safeStringify } from "./validate-resp.js";
import { checkSessionControlEnabled } from "./session-control.js";

export const mcLeaveServerTool = {
    name: "mc_leave_server",
    description: `Leave the current world or server and return to the title
screen. If not in a world, it still resets whatever menu screen is open back
to the title screen. This ends the user's current play session — only do it
when asked, or as part of a dev loop the user set up.

Fire-and-acknowledge: the ack means the disconnect was queued on the game
thread, not that it finished. Requires session_control_enabled=true in the
DebugBridge config.`,
    inputSchema: {
        type: "object" as const,
        properties: {},
        required: [],
    },

    handler: async () => {
        try {
            const disabled = await checkSessionControlEnabled();
            if (disabled) {
                return { content: [{ type: "text" as const, text: disabled }], isError: true };
            }

            const resp = await bridgeSession.send("disconnect", {});
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return {
                content: [{
                    type: "text" as const,
                    text: `Disconnect queued: ${safeStringify(resp.result)}`,
                }],
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
