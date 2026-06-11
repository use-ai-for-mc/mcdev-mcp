import { bridgeSession } from "./session.js";
import { requireResult, safeStringify } from "./validate-resp.js";

export const mcSnapshotTool = {
    name: "mc_snapshot",
    description: `Get a structured snapshot of the current game state. Returns player
position, health, food, dimension, game mode, time of day, weather, etc.
No scripting needed - quick overview of current state.`,
    inputSchema: {
        type: "object" as const,
        properties: {},
        required: [],
    },

    handler: async () => {
        try {
            const resp = await bridgeSession.send("snapshot", {});
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            // requireResult catches the buggy "success but no result" case;
            // safeStringify replaces the cycle-throwing JSON.stringify (rare
            // but real if the bridge ever ships a self-referential payload).
            const result = requireResult(resp, "snapshot");
            return { content: [{ type: "text" as const, text: safeStringify(result) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
