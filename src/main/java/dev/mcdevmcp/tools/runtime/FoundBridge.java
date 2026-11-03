package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.SessionInfo;

/**
 * The selected DebugBridge port and the identity it reported.
 */
value record FoundBridge(int port, SessionInfo info) {
}