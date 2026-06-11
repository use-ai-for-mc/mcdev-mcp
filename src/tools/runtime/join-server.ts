import { bridgeSession } from "./session.js";
import { safeStringify } from "./validate-resp.js";
import {
    checkSessionControlEnabled,
    classifyInWorldPoll,
    waitUntilInWorld,
    DEFAULT_JOIN_TIMEOUT_S,
} from "./session-control.js";

export const mcJoinServerTool = {
    name: "mc_join_server",
    description: `Join a multiplayer server by address ("host" or "host:port").
Disconnects from the current world/server first if needed, so this changes the
user's play session — don't point it at a new server without being asked.
The server's resource pack is pre-accepted by default so the join doesn't
stall on the confirmation prompt.

By default this also polls until the player is actually in-world (the bridge
ack only means "queued"): success when a game snapshot shows a player, failure
when a DisconnectedScreen appears (its title is returned as the reason). When
called from inside a world, the poll first waits for the old session to drop
so a stale snapshot of it can't masquerade as the new join. Set wait=false to
just fire the join and return.

For repeated automated test runs, prefer a local throwaway server over a live
community server — live servers have nondeterministic worlds, other players,
and rules about automation.

Requires session_control_enabled=true in the DebugBridge config.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            address: {
                type: "string",
                description: `Server address, "host" or "host:port" (e.g. "localhost:25565")`,
            },
            acceptResourcePacks: {
                type: "boolean",
                description: "Pre-accept the server resource pack. Default true.",
            },
            wait: {
                type: "boolean",
                description: "Poll until in-world / disconnected before returning. Default true.",
            },
            timeoutSeconds: {
                type: "number",
                description: `How long to wait for the join to complete. Default ${DEFAULT_JOIN_TIMEOUT_S}.`,
            },
        },
        required: ["address"],
    },

    handler: async (args: {
        address: string;
        acceptResourcePacks?: boolean;
        wait?: boolean;
        timeoutSeconds?: number;
    }) => {
        try {
            const disabled = await checkSessionControlEnabled();
            if (disabled) {
                return { content: [{ type: "text" as const, text: disabled }], isError: true };
            }

            // Probe BEFORE sending the join: if we're currently in a world, the
            // outcome poll must see the old session drop before a player
            // snapshot counts as "joined" — the ack only means the
            // disconnect+connect got queued, so early polls still show the old
            // world's player. Unknown state gates nothing — same as joining
            // from the title screen.
            let requireAbsenceFirst = false;
            if (args.wait !== false) {
                try {
                    const pre = await bridgeSession.send("snapshot", {});
                    if (pre.success) {
                        requireAbsenceFirst =
                            classifyInWorldPoll(pre.result, null).state === "joined";
                    }
                } catch { /* can't tell — don't gate */ }
            }

            const resp = await bridgeSession.send("joinServer", {
                address: args.address,
                acceptResourcePacks: args.acceptResourcePacks ?? true,
            });
            if (!resp.success) {
                // Bridge errors here are self-describing (incl. the gated case).
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }

            if (args.wait === false) {
                return {
                    content: [{
                        type: "text" as const,
                        text: `Join queued: ${safeStringify(resp.result)}\n` +
                            `Use mc_wait_until_in_world to confirm the outcome.`,
                    }],
                };
            }

            const timeoutMs = (args.timeoutSeconds ?? DEFAULT_JOIN_TIMEOUT_S) * 1000;
            const outcome = await waitUntilInWorld(timeoutMs, requireAbsenceFirst);
            switch (outcome.state) {
                case "joined":
                    return {
                        content: [{
                            type: "text" as const,
                            text: `Joined ${args.address} — in-world after ${outcome.elapsedSeconds}s.`,
                        }],
                    };
                case "failed":
                    return {
                        content: [{
                            type: "text" as const,
                            text: `Join failed: disconnected from ${args.address}.\nReason: ${outcome.reason}`,
                        }],
                        isError: true,
                    };
                case "timeout":
                    return {
                        content: [{
                            type: "text" as const,
                            text: `Still not in-world after ${outcome.elapsedSeconds}s joining ${args.address} ` +
                                `(no DisconnectedScreen either — possibly a slow login or resource pack download). ` +
                                `Use mc_wait_until_in_world to keep waiting, or mc_screen_inspect to see the current screen.`,
                        }],
                        isError: true,
                    };
            }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
