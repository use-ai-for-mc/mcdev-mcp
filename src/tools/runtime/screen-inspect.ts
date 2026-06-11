import { bridgeSession } from "./session.js";
import { requireResult, safeStringify } from "./validate-resp.js";

export const mcScreenInspectTool = {
    name: "mc_screen_inspect",
    description: `Snapshot the screen the player currently has open. Returns
{open: false} if no screen is displayed; otherwise {open, type, title, ...}.

For container screens (chests, anvils, brewing stands, etc.) also returns
{menuClass, slots: [{idx, container, item:{itemId, count, damage, maxDamage,
name}}]} in a single native pass — cheaper than iterating slots from a script
(one bridge call instead of per-slot traffic).

Set includeIcons=true to also receive a top-level icons map keyed by itemId
({base64Png, width, height, spriteName}) — lets you see every item in the
container in one call. Deduplicated across slots so a chest of stone+dirt
only renders two icons. Adds a few KB to the response per unique item.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            includeIcons: {
                type: "boolean",
                description: "Render each unique item's icon and attach as an icons map. Default false.",
            },
        },
        required: [],
    },

    handler: async (args: { includeIcons?: boolean } = {}) => {
        try {
            const payload: Record<string, unknown> = {};
            if (args.includeIcons !== undefined) payload.includeIcons = args.includeIcons;
            const resp = await bridgeSession.send("screenInspect", payload);
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const result = requireResult(resp, "screenInspect");
            return { content: [{ type: "text" as const, text: safeStringify(result) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
