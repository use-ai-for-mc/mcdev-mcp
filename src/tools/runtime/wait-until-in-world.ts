import { waitUntilInWorld, DEFAULT_JOIN_TIMEOUT_S } from "./session-control.js";

export const mcWaitUntilInWorldTool = {
    name: "mc_wait_until_in_world",
    description: `Poll until the player is in a world (or the join visibly
failed). Checks every second: a game snapshot with a player present means
in-world; a DisconnectedScreen means the join failed and its title is returned
as the reason; anything else keeps waiting until the timeout.

Use after mc_join_server with wait=false, after a relaunch, or whenever you
need to confirm the client finished loading into a world. If you fired the
join from INSIDE a world (mc_join_server wait=false), pass
requireAbsenceFirst=true — otherwise the first polls can still see the old
world's player and report in-world for a join that hasn't happened yet.
Read-only — doesn't require session control to be enabled.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            timeoutSeconds: {
                type: "number",
                description: `Give up after this many seconds. Default ${DEFAULT_JOIN_TIMEOUT_S}.`,
            },
            requireAbsenceFirst: {
                type: "boolean",
                description: "Only count a player snapshot as in-world after the old session " +
                    "visibly dropped (one successful snapshot without a player). Use when a " +
                    "join was issued from inside a world. Default false.",
            },
        },
        required: [],
    },

    handler: async (args: { timeoutSeconds?: number; requireAbsenceFirst?: boolean } = {}) => {
        try {
            const timeoutMs = (args.timeoutSeconds ?? DEFAULT_JOIN_TIMEOUT_S) * 1000;
            const outcome = await waitUntilInWorld(timeoutMs, args.requireAbsenceFirst ?? false);
            switch (outcome.state) {
                case "joined":
                    return {
                        content: [{
                            type: "text" as const,
                            text: `In-world after ${outcome.elapsedSeconds}s.`,
                        }],
                    };
                case "failed":
                    return {
                        content: [{
                            type: "text" as const,
                            text: `Join failed — DisconnectedScreen shown.\nReason: ${outcome.reason}`,
                        }],
                        isError: true,
                    };
                case "timeout":
                    return {
                        content: [{
                            type: "text" as const,
                            text: `Not in-world after ${outcome.elapsedSeconds}s and no DisconnectedScreen. ` +
                                `Use mc_screen_inspect to see what screen the client is on.`,
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
