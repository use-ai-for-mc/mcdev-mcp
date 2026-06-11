import { bridgeSession } from "./session.js";

export const mcNearbyEntitiesTool = {
    name: "mc_nearby_entities",
    description: `Get nearby entities in the world (mobs, items, projectiles, players, etc.).
Returns id, type (Mojang class name), position, distance, and a
primaryEquipment summary (held item / framed item / displayed item) where
applicable.

Prefer this over iterating entities via mc_execute — no script round-trip,
and the summary shape is already curated. (If you do script it, batch the
loop in sync { } .) Use
mc_entity_details to drill into a specific entity by id.

Set includeIcons=true to also receive a top-level icons map keyed by itemId
({base64Png, width, height, spriteName}) for every primaryEquipment item —
lets you see what entities are holding/displaying without per-entity
mc_get_entity_item_texture calls. Deduplicated across entities.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            range: { type: "number", description: "Search radius in blocks. Default 64." },
            limit: { type: "number", description: "Max entries returned. Default 100." },
            includeIcons: {
                type: "boolean",
                description: "Render each unique primaryEquipment item's icon. Default false.",
            },
        },
        required: [],
    },

    handler: async (args: { range?: number; limit?: number; includeIcons?: boolean }) => {
        try {
            const payload: Record<string, unknown> = {};
            if (args.range !== undefined) payload.range = args.range;
            if (args.limit !== undefined) payload.limit = args.limit;
            if (args.includeIcons !== undefined) payload.includeIcons = args.includeIcons;
            const resp = await bridgeSession.send("nearbyEntities", payload);
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            return { content: [{ type: "text" as const, text: JSON.stringify(resp.result, null, 2) }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
