import {
    performRelaunch,
    StageLog,
    LAUNCH_COMMAND_HELP,
    DEFAULT_RELAUNCH_TIMEOUT_S,
} from "./session-control.js";

export const mcRelaunchClientTool = {
    name: "mc_relaunch_client",
    description: `Quit the Minecraft client and launch it again — the restart
half of the mod dev loop (use mc_deploy_and_restart for build+restart in one
call). This tears down the user's entire play session, including any world or
server they're in. Only do it when asked or as part of a dev loop the user set
up.

Stages: quit via the bridge (tolerating the socket dropping), wait for the old
port to close, run the launch command, then poll ports 9876-9886 until the
relaunched instance's bridge answers — verifying the Minecraft version/instance
matches so a second running instance isn't mistaken for it.

Config: launch command from the launchCommand arg or MCDEV_LAUNCH_COMMAND env
var (e.g. \`prismlauncher --launch {instance}\`); {instance} is filled from the
instance arg or MCDEV_INSTANCE. Quitting requires session_control_enabled=true
in the DebugBridge config; launching works even if the client isn't running.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            launchCommand: {
                type: "string",
                description: `Shell command that launches the client. Default: MCDEV_LAUNCH_COMMAND env var. ${LAUNCH_COMMAND_HELP}`,
            },
            instance: {
                type: "string",
                description: "Launcher instance name substituted for {instance}. Default: MCDEV_INSTANCE env var.",
            },
            expectedVersion: {
                type: "string",
                description: "Minecraft version the relaunched instance must report (e.g. \"1.21.11\"). Default: the version of the instance being quit.",
            },
            timeoutSeconds: {
                type: "number",
                description: `Overall timeout across all stages. Default ${DEFAULT_RELAUNCH_TIMEOUT_S}.`,
            },
        },
        required: [],
    },

    handler: async (args: {
        launchCommand?: string;
        instance?: string;
        expectedVersion?: string;
        timeoutSeconds?: number;
    } = {}) => {
        const log = new StageLog();
        try {
            const { port, info } = await performRelaunch({
                launchCommand: args.launchCommand,
                instance: args.instance,
                expectedVersion: args.expectedVersion,
                timeoutMs: (args.timeoutSeconds ?? DEFAULT_RELAUNCH_TIMEOUT_S) * 1000,
            }, log);
            return {
                content: [{
                    type: "text" as const,
                    text: `Relaunched: Minecraft ${info.version} on port ${port}.\n` +
                        `The client is at the title screen — use mc_join_server to get back into a world.\n\n` +
                        `Progress:\n${log.text()}`,
                }],
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            const progress = log.lines.length > 0 ? `\n\nProgress before failure:\n${log.text()}` : "";
            return { content: [{ type: "text" as const, text: `${msg}${progress}` }], isError: true };
        }
    }
};
