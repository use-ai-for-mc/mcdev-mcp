export interface BridgeRequest {
    id: string;
    type: "execute" | "search" | "snapshot" | "screenshot" | "runCommand" | "status"
        | "screenInspect" | "disconnect" | "joinServer" | "quit";
    payload: Record<string, unknown>;
}

export interface BridgeResponse {
    id: string;
    success: boolean;
    result?: unknown;
    output?: string;
    error?: string;
}

export interface SessionInfo {
    version: string;
    mappingStatus: "mojang" | "passthrough";
    obfuscated: boolean;
    refs: number;
    /** Absolute path to the game run directory (.minecraft / profile root). */
    gameDir?: string;
    /** Absolute path to the logs directory inside gameDir. */
    logsDir?: string;
    /** Absolute path to latest.log - use the Read tool to view it. */
    latestLog?: string;
    latestLogExists?: boolean;
    /** Absolute path to debug.log - use the Read tool to view it. */
    debugLog?: string;
    debugLogExists?: boolean;
    /**
     * Whether the bridge's session-control endpoints (disconnect / joinServer /
     * quit) are enabled via `session_control_enabled` in
     * `<gameDir>/config/debugbridge.json`. Absent on bridges that predate the
     * feature; false is the bridge default.
     */
    sessionControlEnabled?: boolean;
}

export interface SearchResult {
    type: "class" | "method" | "field";
    name: string;
    owner?: string;
    signature?: string;
}
