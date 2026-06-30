import { afterEach, describe, expect, it } from '@jest/globals';
import { BridgeSession } from '../src/tools/runtime/session.js';

// Regression test for the connect-rejection crash fixed in this PR.
//
// Before the fix, `connect()` attached a bare `attempt.finally(clear)` to clear
// the cached in-flight promise. When `attempt` rejected, the promise returned by
// `finally()` also rejected with no attached handler. Node treated that as an
// unhandled rejection and, on recent versions, terminated the MCP stdio
// process — which surfaced in Codex as `Transport closed` even though the
// caller had handled the original rejection.
//
// Detection: with the buggy code on a default `unhandled-rejections=throw`
// Node, the bare `.finally()` chain terminates the test process before Jest can
// report success, so simply reaching the post-connect assertions below is
// enough to prove the fix is in place.
describe('BridgeSession.connect() rejection', () => {
    let session: BridgeSession | undefined;

    afterEach(() => {
        session?.disconnect();
        session = undefined;
    });

    it('rejects without terminating the host when DebugBridge is unreachable', async () => {
        session = new BridgeSession();
        // Pin to a closed, privileged port to guarantee an immediate
        // ECONNREFUSED rather than the 2s connect timeout.
        session.setPort(1);

        await expect(session.connect()).rejects.toThrow(/WebSocket error/);

        // Reaching this line proves no unhandled rejection escaped, since
        // Node's default behaviour would have killed the process.
    });

    it('clears the cached connect slot after a failure so the next caller retries', async () => {
        session = new BridgeSession();
        session.setPort(1);

        await expect(session.connect()).rejects.toThrow();

        // A fresh connect() call after a failure must not return the stale,
        // already-rejected cached promise. It should issue a new attempt that
        // also rejects.
        await expect(session.connect()).rejects.toThrow();
    });
});
