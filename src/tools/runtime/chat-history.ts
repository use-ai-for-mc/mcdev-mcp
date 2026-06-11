import { bridgeSession } from "./session.js";
import { requireResult, safeStringify } from "./validate-resp.js";

export const mcChatHistoryTool = {
    name: "mc_chat_history",
    description: `Get the most recent client-side chat messages — what the user has
seen in the chat overlay, including system messages and command output.

Prefer this over walking mc.gui.getChat().allMessages from Groovy (which
costs one bridge round-trip per field on each message). Returns {plain, addedTime}
per message, newest-first. Set includeJson=true to also receive each message
as a structured Component (preserves colors, styles, click events, hover
events) — handy for parsing colored command output.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            limit: { type: "number", description: "Max messages returned. Default 50." },
            includeJson: {
                type: "boolean",
                description: "Include the Component as JSON for each message. Default false.",
            },
        },
        required: [],
    },

    handler: async (args: { limit?: number; includeJson?: boolean }) => {
        try {
            const payload: Record<string, unknown> = {};
            if (args.limit !== undefined) payload.limit = args.limit;
            if (args.includeJson !== undefined) payload.includeJson = args.includeJson;
            const resp = await bridgeSession.send("chatHistory", payload);
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const result = requireResult(resp, "chatHistory");
            return { content: [{ type: "text" as const, text: safeStringify(result) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
