import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

export interface McdevResource {
  uri: string;
  name: string;
  title: string;
  description: string;
  mimeType: string;
  /** Path relative to the package root (i.e. the dir holding package.json). */
  relPath: string;
}

export const resources: McdevResource[] = [
  {
    uri: 'mcdev://guides/python-scripting',
    name: 'python-scripting',
    title: 'Python client guide for the Minecraft DebugBridge',
    description:
      'How to drive a live Minecraft instance from Python by speaking the ' +
      'DebugBridge WebSocket protocol directly — wire format, a minimal ' +
      'asyncio client, and the Groovy surface you send through it. Read this ' +
      'before writing a Python script that bypasses the mcdev-mcp tools.',
    mimeType: 'text/markdown',
    relPath: 'resources/python-scripting.md',
  },
  {
    uri: 'mcdev://guides/dev-loop',
    name: 'dev-loop',
    title: 'The mod dev loop: build → deploy → relaunch → rejoin',
    description:
      'How a coding agent runs the full mod test loop without human ' +
      'interaction: discover the launcher and instance from the bridge\'s ' +
      'gameDir, deploy the jar, quit via mc_quit_client, launch detached ' +
      'from the shell, reconnect with mc_wait_for_bridge, and rejoin a ' +
      'server. Read this before restarting the Minecraft client or ' +
      'automating mod testing.',
    mimeType: 'text/markdown',
    relPath: 'resources/dev-loop.md',
  },
];

function packageRoot(): string {
  // `dist/resources/index.js` and `src/resources/index.ts` both sit two
  // levels below the package root. Same trick as `utils/pkg-version.ts`.
  const here = path.dirname(fileURLToPath(import.meta.url));
  return path.resolve(here, '..', '..');
}

export function readResource(uri: string): { text: string; mimeType: string } {
  const entry = resources.find(r => r.uri === uri);
  if (!entry) {
    throw new Error(`Unknown resource URI: ${uri}`);
  }
  const abs = path.resolve(packageRoot(), entry.relPath);
  const text = fs.readFileSync(abs, 'utf8');
  return { text, mimeType: entry.mimeType };
}

export const SERVER_INSTRUCTIONS = [
  'mcdev-mcp gives AI coding agents two surfaces on a Minecraft codebase:',
  'static analysis of decompiled sources (mc_search, mc_get_class/method, mc_find_refs, …)',
  'and live runtime interaction via the DebugBridge mod (mc_execute, mc_snapshot, …).',
  '',
  'When you, as a coding agent, are about to write a Python script that talks',
  'to the Minecraft backend directly (i.e. opening the DebugBridge WebSocket',
  'yourself instead of using these MCP tools), first read the resource',
  '`mcdev://guides/python-scripting`. It documents the wire protocol, a minimal',
  'asyncio client, and the pitfalls — saves you reverse‑engineering the bridge',
  'from the tool implementations.',
  '',
  'When asked to test mod changes in the running game — rebuild, redeploy,',
  'restart the Minecraft client, rejoin a server — first read the resource',
  '`mcdev://guides/dev-loop`. It covers discovering the launcher/instance from',
  'the bridge\'s gameDir, deploying the jar, and driving mc_quit_client /',
  'mc_wait_for_bridge / mc_join_server, plus the failure playbook.',
].join('\n');
