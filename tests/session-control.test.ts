import { describe, expect, it } from '@jest/globals';
import {
    classifyInWorldPoll,
    instanceMatches,
    resolveLaunchCommand,
    resolveDeployCommand,
    sessionControlDisabledMessage,
    tail,
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

describe('resolveLaunchCommand', () => {
    it('substitutes {instance} from args', () => {
        expect(resolveLaunchCommand({ launchCommand: 'prismlauncher --launch {instance}', instance: 'dev 1.21' }, {}))
            .toBe('prismlauncher --launch dev 1.21');
    });

    it('reads command and instance from env when args are absent', () => {
        const env = { MCDEV_LAUNCH_COMMAND: 'prismlauncher --launch "{instance}"', MCDEV_INSTANCE: 'main' };
        expect(resolveLaunchCommand({}, env)).toBe('prismlauncher --launch "main"');
    });

    it('args take precedence over env', () => {
        const env = { MCDEV_LAUNCH_COMMAND: 'wrong', MCDEV_INSTANCE: 'wrong' };
        expect(resolveLaunchCommand({ launchCommand: 'minecraft-launcher' }, env)).toBe('minecraft-launcher');
    });

    it('passes a command without the placeholder through unchanged', () => {
        expect(resolveLaunchCommand({ launchCommand: './start.sh' }, {})).toBe('./start.sh');
    });

    it('throws a config-pointing error when no command is set', () => {
        expect(() => resolveLaunchCommand({}, {})).toThrow(/MCDEV_LAUNCH_COMMAND/);
    });

    it('throws when {instance} is used but no instance is configured', () => {
        expect(() => resolveLaunchCommand({ launchCommand: 'x --launch {instance}' }, {}))
            .toThrow(/MCDEV_INSTANCE/);
    });
});

describe('resolveDeployCommand', () => {
    it('prefers the arg, falls back to env, and errors with config help', () => {
        expect(resolveDeployCommand({ deployCommand: './deploy.sh' }, { MCDEV_DEPLOY_COMMAND: 'env' })).toBe('./deploy.sh');
        expect(resolveDeployCommand({}, { MCDEV_DEPLOY_COMMAND: './deploy.sh' })).toBe('./deploy.sh');
        expect(() => resolveDeployCommand({}, {})).toThrow(/MCDEV_DEPLOY_COMMAND/);
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

describe('tail', () => {
    it('returns short text unchanged', () => {
        expect(tail('hello', 100)).toBe('hello');
    });

    it('keeps the end of long text and marks the truncation', () => {
        const t = tail('a'.repeat(50) + 'THE-END', 10);
        expect(t).toContain('truncated');
        expect(t.endsWith('THE-END')).toBe(true);
    });
});
