import WebSocket from "ws";
import { BridgeRequest, BridgeResponse, SessionInfo } from "./types.js";

const DEFAULT_PORT = (() => {
    const raw = process.env.DEBUGBRIDGE_PORT;
    if (!raw) return 9876;
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 && n <= 65535 ? n : 9876;
})();

// Hard ceiling on the total wait time for a single bridge request. The user can
// pass a per-call `timeoutMs` (used by mc_execute), but we still cap it so a
// runaway value can't pin a request open for hours. 5 minutes is well past any
// reasonable Groovy script.
const MAX_REQUEST_TIMEOUT_MS = 5 * 60 * 1000;

export class BridgeSession {
    private ws: WebSocket | null = null;
    private requestCounter = 0;
    private pendingRequests = new Map<string, {
        resolve: (resp: BridgeResponse) => void;
        reject: (err: Error) => void;
    }>();
    private configuredPort: number;
    private connectedPort: number | null = null;
    private autoScan: boolean = true;
    // In-flight connect, shared across concurrent send() callers so they don't
    // each open their own WebSocket and race for `this.ws`. Cleared once the
    // attempt settles (success or failure) so the next caller gets a fresh try.
    private connectPromise: Promise<SessionInfo> | null = null;

    // Tracked session info for reconnect verification
    private expectedGameDir: string | null = null;
    private lastSessionInfo: SessionInfo | null = null;

    constructor(defaultPort: number = DEFAULT_PORT) {
        this.configuredPort = defaultPort;
    }

    /** Set port without connecting. Resets expected game instance. */
    setPort(port: number) {
        this.configuredPort = port;
        this.autoScan = false;
        this.expectedGameDir = null;
    }

    /** Get the currently connected port, or null if not connected */
    getConnectedPort(): number | null {
        return this.connectedPort;
    }

    /** Get last session info (version, paths, etc.) */
    getSessionInfo(): SessionInfo | null {
        return this.lastSessionInfo;
    }

    async connect(port?: number): Promise<SessionInfo> {
        // Default-port connects (the common case for parallel send() callers)
        // share an in-flight promise so we never open multiple WebSockets at
        // once. An explicit port argument is treated as a fresh user request
        // and bypasses the cache.
        if (this.connectPromise && port === undefined) {
            return this.connectPromise;
        }

        const attempt = this.doConnect(port);
        this.connectPromise = attempt;
        // Clear the slot once this attempt settles, regardless of outcome.
        // Retain the early-binding so a concurrent successful attempt can't
        // be cleared by an older failure.
        attempt.finally(() => {
            if (this.connectPromise === attempt) {
                this.connectPromise = null;
            }
        });
        return attempt;
    }

    private async doConnect(port?: number): Promise<SessionInfo> {
        if (port !== undefined) {
            this.configuredPort = port;
            this.autoScan = false;
            this.expectedGameDir = null;
        }

        let info: SessionInfo;
        if (this.autoScan) {
            info = await this.connectWithScan();
        } else {
            info = await this.connectToPort(this.configuredPort);
        }

        // Verify same game instance on reconnect.
        //
        // This is intentionally **warn-only** rather than a thrown error: users
        // legitimately switch between game instances (e.g. relaunching
        // Minecraft on a different port). The escape hatch is `reset()`, which
        // clears `expectedGameDir`. Callers who need the mismatch to be visible
        // can read `lastSessionInfo` against the prior value.
        if (this.expectedGameDir && info.gameDir && info.gameDir !== this.expectedGameDir) {
            console.error(
                `[DebugBridge] Connected to a different game instance than before.\n` +
                `  Previous: ${this.expectedGameDir}\n` +
                `  Current:  ${info.gameDir}\n` +
                `  If this is intentional you can ignore this message; otherwise call mc_connect with reset:true.`
            );
        }

        if (info.gameDir) {
            this.expectedGameDir = info.gameDir;
        }
        this.lastSessionInfo = info;

        return info;
    }

    private async connectWithScan(): Promise<SessionInfo> {
        // The bridge binds the first free port in 9876-9886 (with wraparound),
        // so the scan covers 11 ports from the configured base.
        const portsToTry = Array.from({ length: 11 }, (_, i) => this.configuredPort + i);
        let lastError: Error | null = null;

        for (const port of portsToTry) {
            try {
                return await this.connectToPort(port);
            } catch (e) {
                lastError = e instanceof Error ? e : new Error(String(e));
            }
        }

        throw new Error(
            `Could not connect to DebugBridge on ports ${this.configuredPort}-${this.configuredPort + 10}. ` +
            `Is Minecraft running with the mod? Last error: ${lastError?.message}`
        );
    }

    /**
     * Connect to a specific port without pinning future auto-reconnects to it
     * (unlike `connect(port)`, which disables auto-scan). Used by relaunch
     * orchestration: the bridge may bind a different port within its range on
     * the next relaunch, so the scan behaviour must survive.
     */
    async adoptPort(port: number): Promise<SessionInfo> {
        this.disconnect();
        const info = await this.connectToPort(port);
        if (info.gameDir) {
            this.expectedGameDir = info.gameDir;
        }
        this.lastSessionInfo = info;
        return info;
    }

    private async connectToPort(targetPort: number): Promise<SessionInfo> {
        return new Promise((resolve, reject) => {
            const wsUrl = `ws://127.0.0.1:${targetPort}`;
            const ws = new WebSocket(wsUrl);

            const timeout = setTimeout(() => {
                ws.close();
                reject(new Error(`Connection timed out connecting to ${wsUrl}`));
            }, 2000);

            ws.on("open", async () => {
                clearTimeout(timeout);
                this.ws = ws;
                this.connectedPort = targetPort;
                this.setupWebSocketHandlers(ws);
                try {
                    const status = await this.send("status", {});
                    resolve(status.result as SessionInfo);
                } catch (e) {
                    reject(e);
                }
            });

            ws.on("error", (err) => {
                clearTimeout(timeout);
                reject(new Error(`WebSocket error: ${err.message}`));
            });
        });
    }

    private setupWebSocketHandlers(ws: WebSocket) {
        ws.on("message", (data: WebSocket.RawData) => {
            const raw = data.toString();
            let resp: unknown;
            try {
                resp = JSON.parse(raw);
            } catch (e) {
                // We can't recover an id from an unparseable message, so the
                // matching pending request will time out via its setTimeout.
                // Log so the failure is at least visible in stderr / MCP logs.
                const preview = raw.length > 200 ? `${raw.slice(0, 200)}…` : raw;
                console.error(`[DebugBridge] Failed to parse bridge message as JSON: ${e instanceof Error ? e.message : String(e)} — payload: ${preview}`);
                return;
            }

            if (
                !resp ||
                typeof resp !== "object" ||
                typeof (resp as { id?: unknown }).id !== "string"
            ) {
                console.error(`[DebugBridge] Bridge response missing string 'id' field, ignoring: ${raw.slice(0, 200)}`);
                return;
            }

            const id = (resp as { id: string }).id;
            const pending = this.pendingRequests.get(id);
            if (pending) {
                this.pendingRequests.delete(id);
                pending.resolve(resp as BridgeResponse);
            } else {
                // Late response or response to a request from before a reconnect.
                // Not a hard error, but worth logging.
                console.error(`[DebugBridge] No pending request matches response id ${id}`);
            }
        });

        ws.on("close", () => {
            this.ws = null;
            this.connectedPort = null;
            for (const [, pending] of this.pendingRequests) {
                pending.reject(new Error("Connection closed"));
            }
            this.pendingRequests.clear();
        });
    }

    async send(type: string, payload: Record<string, unknown>, timeoutMs?: number): Promise<BridgeResponse> {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            await this.connect();
        }

        // Capture this.ws to a local const after the connect()/readyState check
        // so we can't trip a TypeError if the close event fires between the
        // check and ws.send() — the previous `this.ws!.send(...)` was a real
        // crash hazard under concurrent close.
        const ws = this.ws;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            throw new Error("BridgeSession: connection not open after connect()");
        }

        const id = `req_${++this.requestCounter}`;
        const req: BridgeRequest = { id, type: type as BridgeRequest["type"], payload };

        const requestedDeadline = timeoutMs && timeoutMs > 0 ? timeoutMs + 5000 : 10000;
        const deadlineMs = Math.min(requestedDeadline, MAX_REQUEST_TIMEOUT_MS);
        const cappedNote = requestedDeadline > MAX_REQUEST_TIMEOUT_MS
            ? ` (capped from ${requestedDeadline}ms by BridgeSession ceiling of ${MAX_REQUEST_TIMEOUT_MS}ms)`
            : "";

        return new Promise<BridgeResponse>((resolve, reject) => {
            const timeout = setTimeout(() => {
                this.pendingRequests.delete(id);
                reject(new Error(
                    `Request timed out after ${deadlineMs}ms${cappedNote}. ` +
                    `The game may be frozen or the script may be in an infinite loop.`
                ));
            }, deadlineMs);

            this.pendingRequests.set(id, {
                resolve: (resp) => { clearTimeout(timeout); resolve(resp); },
                reject: (err) => { clearTimeout(timeout); reject(err); }
            });

            try {
                ws.send(JSON.stringify(req));
            } catch (e) {
                // Synchronous send failure (e.g. socket closed since the readyState
                // check above). Tear the pending entry down and propagate.
                this.pendingRequests.delete(id);
                clearTimeout(timeout);
                reject(new Error(`Failed to send to bridge: ${e instanceof Error ? e.message : String(e)}`));
            }
        });
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.connectedPort = null;
        this.pendingRequests.clear();
    }

    /** Full reset - clears all state including expected instance */
    reset() {
        this.disconnect();
        this.expectedGameDir = null;
        this.lastSessionInfo = null;
        this.autoScan = true;
        this.connectPromise = null;
    }

    get isConnected(): boolean {
        return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
    }
}

// Singleton session instance
export const bridgeSession = new BridgeSession();
