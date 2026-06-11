import { describe, expect, it } from '@jest/globals';
import {
    classifyInWorldPoll,
    instanceMatches,
    sessionControlDisabledMessage,
    stepInWorldWait,
    type InWorldWaitProgress,
} from '../src/tools/runtime/session-control.js';
import type { SessionInfo } from '../src/tools/runtime/types.js';

describe('classifyInWorldPoll', () => {
    it('reports joined when the snapshot has a player', () => {
        expect(classifyInWorldPoll({ player: { x: 0, y: 64, z: 0 } }, null))
            .toEqual({ state: 'joined' });
    });

    it('joined wins even if a screen is also open (e.g. chat or pause)', () => {
        expect(classifyInWorldPoll({ player: {} }, { type: 'ChatScreen' }))
            .toEqual({ state: 'joined' });
    });

    it('reports failed with the screen title on a DisconnectedScreen', () => {
        const screen = { type: 'net.minecraft.client.gui.screens.DisconnectedScreen', title: 'Connection refused' };
        expect(classifyInWorldPoll({ }, screen))
            .toEqual({ state: 'failed', reason: 'Connection refused' });
    });

    it('falls back to the screen type when the title is missing or empty', () => {
        expect(classifyInWorldPoll(null, { type: 'DisconnectedScreen', title: '' }))
            .toEqual({ state: 'failed', reason: 'DisconnectedScreen' });
    });

    it('is pending on the title screen', () => {
        expect(classifyInWorldPoll({ }, { type: 'TitleScreen' })).toEqual({ state: 'pending' });
    });

    it('is pending when both results are unavailable (transient bridge errors)', () => {
        expect(classifyInWorldPoll(null, null)).toEqual({ state: 'pending' });
    });

    it('does not treat a falsy player field as in-world', () => {
        expect(classifyInWorldPoll({ player: null }, null)).toEqual({ state: 'pending' });
    });
});

describe('instanceMatches', () => {
    const info = (version: string, gameDir?: string): SessionInfo =>
        ({ version, gameDir, mappingStatus: 'mojang', obfuscated: false, refs: 0 });

    it('prefers gameDir when both sides have it', () => {
        expect(instanceMatches(info('1.21.11', '/mc/a'), { version: '1.21.11', gameDir: '/mc/b' })).toBe(false);
        expect(instanceMatches(info('1.19', '/mc/a'), { gameDir: '/mc/a' })).toBe(true);
    });

    it('falls back to version when gameDir is unavailable', () => {
        expect(instanceMatches(info('1.19'), { version: '1.21.11' })).toBe(false);
        expect(instanceMatches(info('1.21.11'), { version: '1.21.11' })).toBe(true);
    });

    it('accepts anything when nothing is expected', () => {
        expect(instanceMatches(info('1.19'), {})).toBe(true);
    });
});

describe('sessionControlDisabledMessage', () => {
    it('points at the concrete config file when gameDir is known', () => {
        const msg = sessionControlDisabledMessage('/home/u/.minecraft');
        expect(msg).toContain('/home/u/.minecraft/config/debugbridge.json');
        expect(msg).toContain('"session_control_enabled": true');
        expect(msg).toMatch(/restart/i);
    });

    it('uses a placeholder path when gameDir is unknown', () => {
        expect(sessionControlDisabledMessage(undefined)).toContain('<minecraft>/config/debugbridge.json');
    });
});

describe('stepInWorldWait', () => {
    const inWorld = { player: { x: 0, y: 64, z: 0 } };
    const noWorld = { world: { dayTime: 0 } }; // successful snapshot, no player
    const fresh = (): InWorldWaitProgress => ({ sawAbsence: false });

    it('gates a stale player snapshot until the old session visibly drops', () => {
        // Regression: joining server B while on server A reported "joined"
        // off A's still-present player before the queued disconnect ran.
        const progress = fresh();
        expect(stepInWorldWait(progress, true, inWorld, null)).toEqual({ state: 'pending' });
        expect(stepInWorldWait(progress, true, inWorld, null)).toEqual({ state: 'pending' });
        expect(stepInWorldWait(progress, true, noWorld, null)).toEqual({ state: 'pending' });
        expect(progress.sawAbsence).toBe(true);
        expect(stepInWorldWait(progress, true, inWorld, null)).toEqual({ state: 'joined' });
    });

    it('reports joined immediately when absence is not required (title-screen join)', () => {
        expect(stepInWorldWait(fresh(), false, inWorld, null)).toEqual({ state: 'joined' });
    });

    it('never gates a failure: a DisconnectedScreen after the ack is always fresh', () => {
        const progress = fresh();
        const screen = { type: 'DisconnectedScreen', title: 'Connection refused' };
        expect(stepInWorldWait(progress, true, noWorld, screen))
            .toEqual({ state: 'failed', reason: 'Connection refused' });
    });

    it('does not count a transient snapshot failure as absence', () => {
        const progress = fresh();
        expect(stepInWorldWait(progress, true, null, null)).toEqual({ state: 'pending' });
        expect(progress.sawAbsence).toBe(false);
        // ...so a following stale player snapshot still doesn't count as joined.
        expect(stepInWorldWait(progress, true, inWorld, null)).toEqual({ state: 'pending' });
    });
});
