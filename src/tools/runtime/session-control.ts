import net from "net";
import WebSocket from "ws";
import { bridgeSession } from "./session.js";
import { SessionInfo } from "./types.js";

/**
 * Shared plumbing for the session-control tools (mc_join_server,
 * mc_leave_server, mc_quit_client, mc_wait_until_in_world,
 * mc_wait_for_bridge).
 *
 * The bridge's disconnect/joinServer/quit endpoints are fire-and-acknowledge:
 * success=true means "queued on the game thread", not "completed". Everything
 * here exists to turn those acks into observable outcomes — polling snapshot /
 * screenInspect for join results, and probing the port range to find the
 * bridge again after a client relaunch.
 *
 * Deliberately *not* here: running the build/deploy or launching the client.
 * Those are machine-specific shell concerns that the coding agent driving
 * this server handles itself — see the `mcdev://guides/dev-loop` resource for
 * the full build → deploy → relaunch → rejoin procedure.
 */

/** The bridge binds the first free port in this inclusive range (wraparound). */
export const BRIDGE_PORT_START = 9876;
export const BRIDGE_PORT_END = 9886;

export const DEFAULT_JOIN_TIMEOUT_S = 60;
export const DEFAULT_QUIT_TIMEOUT_S = 30;
export const DEFAULT_BRIDGE_WAIT_TIMEOUT_S = 120;

const POLL_INTERVAL_MS = 1000;

export function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
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

/** Mutable cross-tick state for {@link stepInWorldWait}. */
export interface InWorldWaitProgress {
    /** True once a successful snapshot showed no player — i.e. the pre-join
     *  session has actually ended. */
    sawAbsence: boolean;
}

/**
 * One polling tick of the in-world wait, as a pure step over
 * {@link classifyInWorldPoll} so the stale-snapshot gating is unit-testable.
 *
 * The bridge's joinServer ack only means the disconnect+connect got *queued*
 * on the game thread — when the caller was already in a world, the first
 * polls can still see the OLD world's player and would report "joined" for a
 * connection that never happened. With `requireAbsenceFirst`, a
 * player-bearing snapshot only counts as joined after at least one successful
 * snapshot WITHOUT a player (the old session dropping).
 *
 * "failed" is never gated: a world can't display a DisconnectedScreen, so any
 * one seen after the ack is fresh. Transient nulls (the snapshot request
 * itself failed) are evidence of nothing and never count as absence.
 */
export function stepInWorldWait(
    progress: InWorldWaitProgress,
    requireAbsenceFirst: boolean,
    snapshotResult: unknown,
    screenResult: unknown,
): InWorldPollState {
    const cls = classifyInWorldPoll(snapshotResult, screenResult);
    if (snapshotResult !== null && typeof snapshotResult === "object" && cls.state !== "joined") {
        progress.sawAbsence = true;
    }
    if (cls.state === "joined" && requireAbsenceFirst && !progress.sawAbsence) {
        return { state: "pending" };
    }
    return cls;
}

/**
 * Poll snapshot + screenInspect every second until the player is in a world,
 * a DisconnectedScreen appears, or the timeout elapses. Transient bridge
 * errors (e.g. a request timing out while the client is busy loading chunks)
 * are swallowed and polling continues — only the deadline ends the loop.
 *
 * Pass `requireAbsenceFirst` when the join was issued from inside a world —
 * see {@link stepInWorldWait} for why a bare player snapshot can't be
 * trusted until the old session has visibly dropped.
 */
export async function waitUntilInWorld(
    timeoutMs: number,
    requireAbsenceFirst = false,
): Promise<WaitUntilInWorldResult> {
    const start = Date.now();
    const elapsedSeconds = () => Math.round((Date.now() - start) / 100) / 10;
    const progress: InWorldWaitProgress = { sawAbsence: false };

    for (;;) {
        let snapshotResult: unknown = null;
        try {
            const snap = await bridgeSession.send("snapshot", {});
            if (snap.success) snapshotResult = snap.result;
        } catch { /* transient — keep polling */ }

        if (stepInWorldWait(progress, requireAbsenceFirst, snapshotResult, null).state === "joined") {
            return { state: "joined", elapsedSeconds: elapsedSeconds() };
        }

        let screenResult: unknown = null;
        try {
            const scr = await bridgeSession.send("screenInspect", {});
            if (scr.success) screenResult = scr.result;
        } catch { /* transient — keep polling */ }

        const cls = stepInWorldWait(progress, requireAbsenceFirst, snapshotResult, screenResult);
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

/** Poll until nothing listens on the port (the client exited) or the timeout
 *  elapses. Returns true when the port closed. */
export async function waitForPortClosed(port: number, timeoutMs: number): Promise<boolean> {
    const start = Date.now();
    for (;;) {
        if (!(await isPortListening(port))) return true;
        if (Date.now() - start >= timeoutMs) return false;
        await sleep(POLL_INTERVAL_MS);
    }
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
 * instances seen so the failure is debuggable. `note` receives progress
 * remarks (e.g. mismatched instances being skipped).
 */
export async function waitForBridge(
    expected: { version?: string; gameDir?: string },
    timeoutMs: number,
    note?: (msg: string) => void,
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
                note?.(`port ${port} answered with a different instance: ${desc} — skipping`);
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
        `If you just launched the client, check the launcher window: it may be sitting on a ` +
        `login prompt (the user must log in once in the launcher GUI), or the game may have ` +
        `crashed — read <gameDir>/logs/latest.log.`,
    );
}
