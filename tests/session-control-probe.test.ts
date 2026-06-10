import { afterEach, describe, expect, it } from '@jest/globals';
import { WebSocketServer } from 'ws';
import { probePort, isPortListening } from '../src/tools/runtime/session-control.js';

/**
 * probePort against a real (fake-bridge) WebSocket server. This is the piece
 * that decides which instance answered after a relaunch, so it gets a wire
 * test rather than just type-level coverage.
 */

let server: WebSocketServer | null = null;

function startFakeBridge(reply: (req: { id: string; type: string }) => unknown): Promise<number> {
    return new Promise((resolve) => {
        server = new WebSocketServer({ host: '127.0.0.1', port: 0 }, () => {
            const addr = server!.address();
            resolve(typeof addr === 'object' && addr ? addr.port : 0);
        });
        server.on('connection', (ws) => {
            ws.on('message', (data) => {
                const req = JSON.parse(data.toString()) as { id: string; type: string };
                ws.send(JSON.stringify(reply(req)));
            });
        });
    });
}

afterEach((done) => {
    if (server) {
        server.close(() => { server = null; done(); });
        server.clients.forEach((c) => c.terminate());
    } else {
        done();
    }
});

describe('probePort', () => {
    it('resolves the SessionInfo from a status reply', async () => {
        const port = await startFakeBridge((req) => ({
            id: req.id,
            success: true,
            result: { version: '1.21.11', gameDir: '/mc/dev', sessionControlEnabled: true },
        }));
        const info = await probePort(port);
        expect(info.version).toBe('1.21.11');
        expect(info.sessionControlEnabled).toBe(true);
    });

    it('sends a status request, not something else', async () => {
        let seenType: string | null = null;
        const port = await startFakeBridge((req) => {
            seenType = req.type;
            return { id: req.id, success: true, result: { version: 'x' } };
        });
        await probePort(port);
        expect(seenType).toBe('status');
    });

    it('rejects when the bridge reports failure', async () => {
        const port = await startFakeBridge((req) => ({ id: req.id, success: false, error: 'nope' }));
        await expect(probePort(port)).rejects.toThrow(/status failed.*nope/);
    });

    it('rejects quickly when nothing is listening', async () => {
        // Grab a port that is definitely free by opening and closing a server.
        const port = await startFakeBridge(() => ({}));
        await new Promise<void>((resolve) => server!.close(() => { server = null; resolve(); }));
        await expect(probePort(port, 1000)).rejects.toThrow();
    });
});

describe('isPortListening', () => {
    it('distinguishes a listening port from a closed one', async () => {
        const port = await startFakeBridge((req) => ({ id: req.id, success: true, result: {} }));
        expect(await isPortListening(port)).toBe(true);
        await new Promise<void>((resolve) => server!.close(() => { server = null; resolve(); }));
        expect(await isPortListening(port)).toBe(false);
    });
});
