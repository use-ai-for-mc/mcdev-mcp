import { spawn } from "child_process";
import net from "net";
import WebSocket from "ws";
import { bridgeSession } from "./session.js";
import { SessionInfo } from "./types.js";

/**
 * Shared plumbing for the session-control tools (mc_join_server,
 * mc_leave_server, mc_wait_until_in_world, mc_relaunch_client,
 * mc_deploy_and_restart).
 *
 * The bridge's disconnect/joinServer/quit endpoints are fire-and-acknowledge:
 * success=true means "queued on the game thread", not "completed". Everything
 * here exists to turn those acks into observable outcomes — polling snapshot /
 * screenInspect for join results, and probing the port range to find the
 * bridge again after a client relaunch.
 */

/** The bridge binds the first free port in this inclusive range (wraparound). */
export const BRIDGE_PORT_START = 9876;
export const BRIDGE_PORT_END = 9886;

export const DEFAULT_JOIN_TIMEOUT_S = 60;
export const DEFAULT_RELAUNCH_TIMEOUT_S = 120;
export const DEFAULT_DEPLOY_TIMEOUT_S = 600;

const POLL_INTERVAL_MS = 1000;

export function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Timestamped stage-by-stage progress log. MCP tools can only respond once,
 * so multi-stage orchestration (quit → launch → wait for bridge) accumulates
 * its progress here and returns the whole transcript — on failure the caller
 * sees exactly which stage died and how long each one took.
 */
export class StageLog {
    private start = Date.now();
    readonly lines: string[] = [];

    add(msg: string): void {
        const t = ((Date.now() - this.start) / 1000).toFixed(1);
        this.lines.push(`[${t}s] ${msg}`);
    }

    text(): string {
        return this.lines.join("\n");
    }

    elapsedMs(): number {
        return Date.now() - this.start;
    }
}

// ---------------------------------------------------------------------------
// Capability gate
// ---------------------------------------------------------------------------

export function sessionControlDisabledMessage(gameDir?: string | null): string {
    const cfg = gameDir
        ? `${gameDir.replace(/[/\\]$/, "")}/config/debugbridge.json`
        : "<minecraft>/config/debugbridge.json";
    return (
        `Session control is disabled in DebugBridge (session_control_enabled=false, the default).\n` +
        `To enable it: edit ${cfg}, set "session_control_enabled": true, then restart the ` +
        `Minecraft client — the flag is only read at startup.`
    );
}

/**
 * Pre-flight check before calling a gated endpoint. Returns an explanatory
 * error string when the bridge reports session control explicitly disabled,
 * or null when it's enabled / unknown. "Unknown" (older bridge without the
 * status field) deliberately passes: the gated call itself fails with a
 * self-describing error that the tools pass through verbatim.
 */
export async function checkSessionControlEnabled(): Promise<string | null> {
    const info = bridgeSession.isConnected
        ? bridgeSession.getSessionInfo()
        : await bridgeSession.connect();
    if (info && info.sessionControlEnabled === false) {
        return sessionControlDisabledMessage(info.gameDir);
    }
    return null;
}

// ---------------------------------------------------------------------------
// In-world polling (join outcome detection)
// ---------------------------------------------------------------------------

export type InWorldPollState =
    | { state: "joined" }
    | { state: "failed"; reason: string }
    | { state: "pending" };

/**
 * Pure classifier for one polling tick. `snapshotResult.player` present means
 * we're in a world; a screen whose type mentions DisconnectedScreen means the
 * join failed (the screen title is the closest thing to a failure reason the
 * client exposes). Anything else — title screen, connecting screen, transient
 * bridge errors upstream — is "pending".
 */
export function classifyInWorldPoll(
    snapshotResult: unknown,
    screenResult: unknown,
): InWorldPollState {
    if (
        snapshotResult !== null &&
        typeof snapshotResult === "object" &&
        (snapshotResult as Record<string, unknown>).player
    ) {
        return { state: "joined" };
    }
    if (screenResult !== null && typeof screenResult === "object") {
        const scr = screenResult as Record<string, unknown>;
        if (typeof scr.type === "string" && scr.type.includes("DisconnectedScreen")) {
            const title = typeof scr.title === "string" && scr.title.length > 0
                ? scr.title
                : scr.type;
            return { state: "failed", reason: title };
        }
    }
    return { state: "pending" };
}

export interface WaitUntilInWorldResult {
    state: "joined" | "failed" | "timeout";
    /** DisconnectedScreen title when state === "failed". */
    reason?: string;
    elapsedSeconds: number;
}

/**
 * Poll snapshot + screenInspect every second until the player is in a world,
 * a DisconnectedScreen appears, or the timeout elapses. Transient bridge
 * errors (e.g. a request timing out while the client is busy loading chunks)
 * are swallowed and polling continues — only the deadline ends the loop.
 */
export async function waitUntilInWorld(timeoutMs: number): Promise<WaitUntilInWorldResult> {
    const start = Date.now();
    const elapsedSeconds = () => Math.round((Date.now() - start) / 100) / 10;

    for (;;) {
        let snapshotResult: unknown = null;
        try {
            const snap = await bridgeSession.send("snapshot", {});
            if (snap.success) snapshotResult = snap.result;
        } catch { /* transient — keep polling */ }

        if (classifyInWorldPoll(snapshotResult, null).state === "joined") {
            return { state: "joined", elapsedSeconds: elapsedSeconds() };
        }

        let screenResult: unknown = null;
        try {
            const scr = await bridgeSession.send("screenInspect", {});
            if (scr.success) screenResult = scr.result;
        } catch { /* transient — keep polling */ }

        const cls = classifyInWorldPoll(snapshotResult, screenResult);
        if (cls.state === "failed") {
            return { state: "failed", reason: cls.reason, elapsedSeconds: elapsedSeconds() };
        }

        if (Date.now() - start >= timeoutMs) {
            return { state: "timeout", elapsedSeconds: elapsedSeconds() };
        }
        await sleep(POLL_INTERVAL_MS);
    }
}

// ---------------------------------------------------------------------------
// Port probing (relaunch detection)
// ---------------------------------------------------------------------------

/** True if anything is accepting TCP connections on 127.0.0.1:port. */
export function isPortListening(port: number, timeoutMs = 800): Promise<boolean> {
    return new Promise((resolve) => {
        const sock = net.connect({ host: "127.0.0.1", port });
        let settled = false;
        const done = (v: boolean) => {
            if (settled) return;
            settled = true;
            sock.destroy();
            resolve(v);
        };
        sock.once("connect", () => done(true));
        sock.once("error", () => done(false));
        sock.setTimeout(timeoutMs, () => done(false));
    });
}

/**
 * One-shot status query against a port with a fresh, short-lived WebSocket.
 * Doesn't touch the shared bridgeSession — used to identify *which* instance
 * answers on a port before deciding to adopt it.
 */
export function probePort(port: number, timeoutMs = 1500): Promise<SessionInfo> {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${port}`);
        let settled = false;
        const finish = (fn: () => void) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            try { ws.close(); } catch { /* already closed */ }
            fn();
        };
        const timer = setTimeout(
            () => finish(() => reject(new Error(`status probe timed out on port ${port}`))),
            timeoutMs,
        );
        ws.on("open", () => {
            ws.send(JSON.stringify({ id: "probe", type: "status", payload: {} }));
        });
        ws.on("message", (data: WebSocket.RawData) => {
            try {
                const resp = JSON.parse(data.toString()) as {
                    success?: boolean; result?: unknown; error?: string;
                };
                if (resp.success && resp.result && typeof resp.result === "object") {
                    finish(() => resolve(resp.result as SessionInfo));
                } else {
                    finish(() => reject(new Error(
                        `status failed on port ${port}: ${resp.error ?? "no result"}`,
                    )));
                }
            } catch (e) {
                finish(() => reject(e instanceof Error ? e : new Error(String(e))));
            }
        });
        ws.on("error", (err) => finish(() => reject(new Error(err.message))));
    });
}

/** Ports to sweep when looking for a bridge: the documented range, plus the
 *  env-configured base port if it falls outside it. */
export function bridgePortRange(): number[] {
    const ports = Array.from(
        { length: BRIDGE_PORT_END - BRIDGE_PORT_START + 1 },
        (_, i) => BRIDGE_PORT_START + i,
    );
    const raw = process.env.DEBUGBRIDGE_PORT;
    if (raw) {
        const n = Number(raw);
        if (Number.isFinite(n) && n > 0 && n <= 65535 && !ports.includes(n)) {
            ports.unshift(n);
        }
    }
    return ports;
}

/**
 * Does the instance answering a probe look like the one we expect? gameDir is
 * the strongest identity signal (stable across relaunches, unique per
 * instance); version is the fallback when the bridge doesn't report gameDir.
 * With multiple instances running (e.g. 1.21.11 on 9876 and 1.19 on 9877),
 * this is what stops a relaunch wait from latching onto the wrong one.
 */
export function instanceMatches(
    info: SessionInfo,
    expected: { version?: string; gameDir?: string },
): boolean {
    if (expected.gameDir && info.gameDir) {
        return info.gameDir === expected.gameDir;
    }
    if (expected.version) {
        return info.version === expected.version;
    }
    return true;
}

/**
 * Sweep the port range until a bridge matching `expected` answers a status
 * query, then return it (without connecting the shared session — callers
 * adopt the port themselves). Throws on timeout, listing any non-matching
 * instances seen so the failure is debuggable.
 */
export async function waitForBridge(
    expected: { version?: string; gameDir?: string },
    timeoutMs: number,
    log?: StageLog,
): Promise<{ port: number; info: SessionInfo }> {
    const start = Date.now();
    const seenMismatches = new Map<number, string>();

    while (Date.now() - start < timeoutMs) {
        for (const port of bridgePortRange()) {
            let info: SessionInfo;
            try {
                info = await probePort(port);
            } catch {
                continue; // nothing (matching) on this port yet
            }
            if (instanceMatches(info, expected)) {
                return { port, info };
            }
            const desc = `${info.version ?? "?"} (${info.gameDir ?? "unknown gameDir"})`;
            if (seenMismatches.get(port) !== desc) {
                seenMismatches.set(port, desc);
                log?.add(`waiting for bridge: port ${port} answered with a different instance: ${desc} — skipping`);
            }
        }
        await sleep(POLL_INTERVAL_MS);
    }

    const expectedDesc = expected.gameDir ?? expected.version ?? "any instance";
    const seen = seenMismatches.size > 0
        ? ` Other instances answered: ${[...seenMismatches.entries()].map(([p, d]) => `port ${p} → ${d}`).join(", ")}.`
        : "";
    throw new Error(
        `Timed out after ${Math.round(timeoutMs / 1000)}s waiting for the bridge of ${expectedDesc} ` +
        `on ports ${BRIDGE_PORT_START}-${BRIDGE_PORT_END}.${seen} ` +
        `Check the launcher actually started the instance (look at its window / logs).`,
    );
}

// ---------------------------------------------------------------------------
// Command configuration + process helpers
// ---------------------------------------------------------------------------

export const LAUNCH_COMMAND_HELP =
    "Pass `launchCommand` or set the MCDEV_LAUNCH_COMMAND env var, e.g. " +
    "`prismlauncher --launch {instance}`. A literal `{instance}` is replaced " +
    "with the `instance` arg / MCDEV_INSTANCE env var.";

/** Resolve the client launch command from args/env, substituting {instance}. */
export function resolveLaunchCommand(
    args: { launchCommand?: string; instance?: string },
    env: NodeJS.ProcessEnv = process.env,
): string {
    const template = args.launchCommand ?? env.MCDEV_LAUNCH_COMMAND;
    if (!template) {
        throw new Error(`No launch command configured. ${LAUNCH_COMMAND_HELP}`);
    }
    if (template.includes("{instance}")) {
        const instance = args.instance ?? env.MCDEV_INSTANCE;
        if (!instance) {
            throw new Error(
                "The launch command contains {instance} but no instance name is configured. " +
                "Pass `instance` or set the MCDEV_INSTANCE env var.",
            );
        }
        return template.replaceAll("{instance}", instance);
    }
    return template;
}

export function resolveDeployCommand(
    args: { deployCommand?: string },
    env: NodeJS.ProcessEnv = process.env,
): string {
    const command = args.deployCommand ?? env.MCDEV_DEPLOY_COMMAND;
    if (!command) {
        throw new Error(
            "No deploy command configured. Pass `deployCommand` or set the " +
            "MCDEV_DEPLOY_COMMAND env var (e.g. your repo's build-and-deploy script). " +
            "Optional MCDEV_DEPLOY_CWD sets its working directory.",
        );
    }
    return command;
}

/**
 * Start the launcher detached (shell command). Resolves once the process has
 * survived a short grace window or exited 0 (launchers commonly fork the game
 * and exit); rejects on spawn failure or an immediate nonzero exit, which is
 * the "command not found / bad instance name" fast path.
 */
export function launchDetached(command: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = spawn(command, { shell: true, detached: true, stdio: "ignore" });
        child.unref();
        let settled = false;
        const settle = (fn: () => void) => {
            if (settled) return;
            settled = true;
            clearTimeout(grace);
            fn();
        };
        child.once("error", (err) => settle(() =>
            reject(new Error(`Failed to start launch command \`${command}\`: ${err.message}`))));
        child.once("exit", (code) => {
            if (code !== null && code !== 0) {
                settle(() => reject(new Error(
                    `Launch command \`${command}\` exited immediately with code ${code}. ` +
                    `Check the command and instance name.`,
                )));
            } else {
                settle(resolve);
            }
        });
        const grace = setTimeout(() => settle(resolve), 1500);
    });
}

const MAX_CAPTURED_OUTPUT = 200 * 1024;

export interface ShellResult {
    exitCode: number | null;
    /** Combined stdout+stderr, capped at 200 KB (head dropped when over). */
    output: string;
    timedOut: boolean;
}

/** Run a foreground shell command (the deploy step), capturing combined output. */
export function runShellCommand(
    command: string,
    opts: { cwd?: string; timeoutMs: number },
): Promise<ShellResult> {
    return new Promise((resolve) => {
        const child = spawn(command, { shell: true, cwd: opts.cwd, stdio: ["ignore", "pipe", "pipe"] });
        let output = "";
        let timedOut = false;
        const append = (chunk: Buffer) => {
            output += chunk.toString();
            if (output.length > MAX_CAPTURED_OUTPUT) {
                output = output.slice(output.length - MAX_CAPTURED_OUTPUT);
            }
        };
        child.stdout.on("data", append);
        child.stderr.on("data", append);
        const timer = setTimeout(() => {
            timedOut = true;
            child.kill("SIGTERM");
            // Escalate if SIGTERM is ignored (gradle daemons can be stubborn).
            setTimeout(() => child.kill("SIGKILL"), 5000).unref();
        }, opts.timeoutMs);
        child.once("error", (err) => {
            clearTimeout(timer);
            resolve({ exitCode: null, output: `${output}\n[spawn error] ${err.message}`, timedOut });
        });
        child.once("close", (code) => {
            clearTimeout(timer);
            resolve({ exitCode: code, output, timedOut });
        });
    });
}

export function tail(text: string, maxChars = 6000): string {
    const trimmed = text.trimEnd();
    if (trimmed.length <= maxChars) return trimmed;
    return `…(output truncated)…\n${trimmed.slice(trimmed.length - maxChars)}`;
}

// ---------------------------------------------------------------------------
// Relaunch orchestration (shared by mc_relaunch_client and mc_deploy_and_restart)
// ---------------------------------------------------------------------------

export interface RelaunchOptions {
    launchCommand?: string;
    instance?: string;
    /** MC version the relaunched instance must report (e.g. "1.21.11").
     *  Defaults to the version of the instance we quit. */
    expectedVersion?: string;
    timeoutMs: number;
}

/**
 * The full quit → wait-for-shutdown → launch → wait-for-bridge loop.
 * Stage-by-stage progress goes into `log`; throws (with the log already
 * populated) on any stage failure. On success the shared bridgeSession is
 * connected to the relaunched instance.
 */
export async function performRelaunch(
    opts: RelaunchOptions,
    log: StageLog,
): Promise<{ port: number; info: SessionInfo }> {
    const deadline = Date.now() + opts.timeoutMs;
    const remaining = () => deadline - Date.now();

    // Resolve config up front so a missing launch command fails before we
    // tear anything down.
    const launchCommand = resolveLaunchCommand(opts);

    // Identify the current instance (version/gameDir/port) so we can verify
    // we reconnect to the *same* one after the relaunch.
    let previous: SessionInfo | null = null;
    let previousPort: number | null = null;
    try {
        previous = bridgeSession.isConnected
            ? bridgeSession.getSessionInfo()
            : await bridgeSession.connect();
        previousPort = bridgeSession.getConnectedPort();
        log.add(`connected to Minecraft ${previous?.version ?? "?"} on port ${previousPort}`);
    } catch {
        log.add("no running client found on any bridge port — skipping quit, going straight to launch");
    }

    // With multiple instances running, the auto-scan may have attached to the
    // wrong one. Refuse to quit an instance whose version contradicts an
    // explicit expectedVersion — that would tear down the user's *other* session.
    if (previous?.version && opts.expectedVersion && previous.version !== opts.expectedVersion) {
        throw new Error(
            `Connected instance reports Minecraft ${previous.version}, but expectedVersion is ` +
            `${opts.expectedVersion}. Refusing to quit a different instance — use mc_connect ` +
            `(with the right port) to attach to the instance you want to relaunch first.`,
        );
    }

    const expected = {
        version: opts.expectedVersion ?? previous?.version,
        gameDir: previous?.gameDir,
    };

    if (previous) {
        if (previous.sessionControlEnabled === false) {
            throw new Error(sessionControlDisabledMessage(previous.gameDir));
        }

        // Quit. Fire-and-acknowledge; the socket dropping right after (or
        // even instead of) the ack is the expected success mode.
        log.add("quitting: sending quit to the bridge");
        try {
            const resp = await bridgeSession.send("quit", {});
            if (!resp.success) {
                // Self-describing bridge error (e.g. session control gated) — pass through.
                throw new Error(resp.error ?? "quit failed with no error message");
            }
            log.add("quitting: acknowledged, waiting for the client to exit");
        } catch (e) {
            const msg = e instanceof Error ? e.message : String(e);
            if (/connection closed/i.test(msg)) {
                log.add("quitting: socket dropped before the ack — client is shutting down");
            } else {
                throw e;
            }
        }
        bridgeSession.disconnect();

        if (previousPort !== null) {
            const shutdownDeadline = Math.min(30_000, Math.max(remaining(), 0));
            const start = Date.now();
            let closed = false;
            while (Date.now() - start < shutdownDeadline) {
                if (!(await isPortListening(previousPort))) { closed = true; break; }
                await sleep(POLL_INTERVAL_MS);
            }
            if (!closed) {
                throw new Error(
                    `Client did not shut down: port ${previousPort} is still listening after ` +
                    `${Math.round(shutdownDeadline / 1000)}s. The game may be stuck on a save/exit ` +
                    `prompt — please close it manually, then re-run.`,
                );
            }
            log.add(`quitting: port ${previousPort} closed — client is down`);
        }
    }

    if (remaining() <= 0) {
        throw new Error("Relaunch timeout exhausted before the launch stage. Increase timeoutSeconds.");
    }

    log.add(`launching: ${launchCommand}`);
    await launchDetached(launchCommand);
    log.add("launching: launcher started, waiting for the bridge to come up");

    const found = await waitForBridge(expected, Math.max(remaining(), 0), log);
    log.add(`bridge answered on port ${found.port}: Minecraft ${found.info.version}`);

    // Adopt (rather than connect(port)) so future auto-reconnects keep
    // scanning — the port can move again on the next relaunch.
    const info = await bridgeSession.adoptPort(found.port);
    log.add("reconnected to the relaunched client");
    return { port: found.port, info };
}
