import { bridgeSession } from "./session.js";
import {
    checkSessionControlEnabled,
    performRelaunch,
    resolveDeployCommand,
    runShellCommand,
    StageLog,
    tail,
    waitUntilInWorld,
    LAUNCH_COMMAND_HELP,
    DEFAULT_DEPLOY_TIMEOUT_S,
    DEFAULT_JOIN_TIMEOUT_S,
    DEFAULT_RELAUNCH_TIMEOUT_S,
} from "./session-control.js";

export const mcDeployAndRestartTool = {
    name: "mc_deploy_and_restart",
    description: `The one-call mod dev loop: build & deploy the mod, relaunch
the Minecraft client, and optionally rejoin a server and wait until in-world.
This tears down the user's entire play session — only use it when asked or as
part of a dev loop the user set up.

Stages: run the deploy command (fails fast with its output on a nonzero exit),
quit the client via the bridge, wait for the old port to close, run the launch
command, poll ports 9876-9886 until the matching instance's bridge answers,
then — if joinAddress is given — join and poll until in-world.

For repeated automated test runs, prefer joining a local throwaway server over
a live community server (nondeterministic world, other players, server rules).

Config (args override env vars):
- deploy:  deployCommand / MCDEV_DEPLOY_COMMAND, cwd from MCDEV_DEPLOY_CWD
- launch:  launchCommand / MCDEV_LAUNCH_COMMAND with {instance} from instance / MCDEV_INSTANCE
Quitting/joining require session_control_enabled=true in the DebugBridge config.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            deployCommand: {
                type: "string",
                description: "Shell command that builds and deploys the mod. Default: MCDEV_DEPLOY_COMMAND env var.",
            },
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
                description: "Minecraft version the relaunched instance must report. Default: the version of the instance being quit.",
            },
            joinAddress: {
                type: "string",
                description: "Server to join after the relaunch (\"host\" or \"host:port\"). Omit to stop at the title screen.",
            },
            acceptResourcePacks: {
                type: "boolean",
                description: "Pre-accept the server resource pack when joining. Default true.",
            },
            deployTimeoutSeconds: {
                type: "number",
                description: `Timeout for the deploy command. Default ${DEFAULT_DEPLOY_TIMEOUT_S}.`,
            },
            relaunchTimeoutSeconds: {
                type: "number",
                description: `Timeout for the quit+launch+reconnect stages. Default ${DEFAULT_RELAUNCH_TIMEOUT_S}.`,
            },
            joinTimeoutSeconds: {
                type: "number",
                description: `Timeout for the join-and-load stage. Default ${DEFAULT_JOIN_TIMEOUT_S}.`,
            },
        },
        required: [],
    },

    handler: async (args: {
        deployCommand?: string;
        launchCommand?: string;
        instance?: string;
        expectedVersion?: string;
        joinAddress?: string;
        acceptResourcePacks?: boolean;
        deployTimeoutSeconds?: number;
        relaunchTimeoutSeconds?: number;
        joinTimeoutSeconds?: number;
    } = {}) => {
        const log = new StageLog();
        const fail = (msg: string) => ({
            content: [{
                type: "text" as const,
                text: log.lines.length > 0 ? `${msg}\n\nProgress before failure:\n${log.text()}` : msg,
            }],
            isError: true,
        });

        try {
            // ---- deploy ----
            const deployCommand = resolveDeployCommand(args);
            const cwd = process.env.MCDEV_DEPLOY_CWD || undefined;
            log.add(`deploying: ${deployCommand}${cwd ? ` (cwd: ${cwd})` : ""}`);
            const deploy = await runShellCommand(deployCommand, {
                cwd,
                timeoutMs: (args.deployTimeoutSeconds ?? DEFAULT_DEPLOY_TIMEOUT_S) * 1000,
            });
            if (deploy.timedOut) {
                return fail(`Deploy command timed out. Output tail:\n${tail(deploy.output)}`);
            }
            if (deploy.exitCode !== 0) {
                return fail(`Deploy command failed with exit code ${deploy.exitCode}. Output tail:\n${tail(deploy.output)}`);
            }
            log.add("deploying: succeeded");

            // ---- relaunch ----
            const { port, info } = await performRelaunch({
                launchCommand: args.launchCommand,
                instance: args.instance,
                expectedVersion: args.expectedVersion,
                timeoutMs: (args.relaunchTimeoutSeconds ?? DEFAULT_RELAUNCH_TIMEOUT_S) * 1000,
            }, log);

            // ---- optional join ----
            if (!args.joinAddress) {
                return {
                    content: [{
                        type: "text" as const,
                        text: `Deployed and relaunched: Minecraft ${info.version} on port ${port}, at the title screen.\n\n` +
                            `Progress:\n${log.text()}`,
                    }],
                };
            }

            // The relaunch read fresh status; re-check the gate so a config
            // regression surfaces as instructions instead of a raw error.
            const disabled = await checkSessionControlEnabled();
            if (disabled) return fail(disabled);

            log.add(`joining: ${args.joinAddress}`);
            const joinResp = await bridgeSession.send("joinServer", {
                address: args.joinAddress,
                acceptResourcePacks: args.acceptResourcePacks ?? true,
            });
            if (!joinResp.success) {
                return fail(`Join failed: ${joinResp.error}`);
            }
            const outcome = await waitUntilInWorld((args.joinTimeoutSeconds ?? DEFAULT_JOIN_TIMEOUT_S) * 1000);
            if (outcome.state === "failed") {
                return fail(`Joined ${args.joinAddress} but got disconnected.\nReason: ${outcome.reason}`);
            }
            if (outcome.state === "timeout") {
                return fail(
                    `Relaunch succeeded but still not in-world ${outcome.elapsedSeconds}s after joining ` +
                    `${args.joinAddress}. Use mc_screen_inspect to see the current screen.`,
                );
            }
            log.add(`joining: in-world after ${outcome.elapsedSeconds}s`);

            return {
                content: [{
                    type: "text" as const,
                    text: `Dev loop complete: deployed, relaunched Minecraft ${info.version} (port ${port}), ` +
                        `and in-world on ${args.joinAddress}.\n\nProgress:\n${log.text()}`,
                }],
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return fail(msg);
        }
    }
};
