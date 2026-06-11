# mcdev-mcp

[![CI](https://github.com/use-ai-for-mc/mcdev-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/use-ai-for-mc/mcdev-mcp/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

An **MCP (Model Context Protocol) server** that empowers AI coding agents to work effectively with Minecraft mod development. Provides both **static analysis** of decompiled source code and **runtime interaction** with a running Minecraft instance.

## Features

### Static Analysis (work offline)
- **Decompiled Source Access** — Auto-downloads and decompiles Minecraft client using [Vineflower](https://github.com/Vineflower/vineflower)
- **Dev Snapshot Support** — Works with development snapshots (e.g., `26.1-snapshot-10`) that lack ProGuard mappings
- **Symbol Search** — Search for classes, methods, and fields by name (`mc_search`)
- **Source Retrieval** — Get full class source or individual methods with context
- **Package Exploration** — List all classes under a package path or discover available packages
- **Class Hierarchy** — Find subclasses and interface implementors
- **Call Graph Analysis** — Find method callers and callees across the entire codebase

### Runtime Interaction (requires [DebugBridge](https://github.com/use-ai-for-mc/debugbridge) mod)
- **Live Groovy Execution** — Execute Groovy scripts inside the running Minecraft JVM (`mc_execute`); migrated from Lua in mid-2026
- **Game State Snapshots** — Player position, health, dimension, time, weather (`mc_snapshot`)
- **Screenshots, Recordings & Screen Inspection** — Game-window JPEG, multi-frame contact sheet for temporal debug, and current-GUI structure (`mc_screenshot`, `mc_record_video`, `mc_screen_inspect`)
- **World Introspection** — Nearby entities and block-entities, plus per-id details (`mc_nearby_entities`, `mc_entity_details`, `mc_nearby_blocks`, `mc_block_details`, `mc_looked_at_entity`)
- **Visual Markers** — Outline entities or blocks for the user to spot (`mc_set_entity_glow`, `mc_set_block_glow`, `mc_clear_block_glow`)
- **Item Texture Rendering** — Render an inventory slot, an item id, or a slot on another entity as PNG (`mc_get_item_texture`, `mc_get_item_texture_by_id`, `mc_get_entity_item_texture`)
- **Chat History** — Recent client-side chat messages (`mc_chat_history`)
- **Session Control & Dev Loop** — Join/leave servers, quit the client, and reconnect after a relaunch (`mc_join_server`, `mc_leave_server`, `mc_quit_client`, `mc_wait_for_bridge`, `mc_wait_until_in_world`; gated by `session_control_enabled` in the DebugBridge config). Build/launch orchestration is the coding agent's job, guided by the `mcdev://guides/dev-loop` resource and the `minecraft-dev-loop` skill.
- **Slash Commands** — Execute in-game commands (`mc_run_command`, opt-in dev tool)
- **Script Execution Logs** — Review past `mc_execute` runs and error patterns (`mc_script_logs`, opt-in via Claude Desktop user setting)

### MCP Resources
- **`mcdev://guides/python-scripting`** — Wire-protocol reference for AI agents that want to drive DebugBridge from Python directly (bypassing the MCP tools): WebSocket framing, a minimal asyncio client, and the Groovy surface you send through it. Surfaced via the standard MCP `resources/list` + `resources/read`, with a pointer in the server's `instructions` so agents know to look.

## Quick Start

> **Security note — `init` is intentionally terminal-only.** The MCP server only exposes read/query tools. Downloading and decompiling Minecraft sources must be triggered by you in the terminal; an AI agent connected to the server has no tool surface to trigger `init`, `rebuild`, `clean`, or `callgraph`.

### 1. Initialize in your terminal

```bash
# Download, decompile, and index Minecraft sources (~2-5 minutes)
npx mcdev-mcp init -v 1.21.11
```

This command:
1. Downloads the Minecraft client JAR
2. Decompiles using Vineflower (pure Java, 8 threads)
3. Builds the symbol index (classes, methods, fields, inheritance)
4. Generates call graph for `mc_find_refs`

Data is stored in your OS cache directory (see [Storage location](#storage-location) below), so it persists across `npx` invocations. Expect roughly **~2 GB per Minecraft version** — mostly decompiled `.java` sources and a SQLite callgraph database. All of it is regeneratable, so your OS is free to evict it under storage pressure and `init` will rebuild what it needs.

### 2. Add to your MCP client

#### Codex Desktop / Codex CLI

Codex can launch local stdio MCP servers directly. Install the published package with:

```bash
codex mcp add mcdev-mcp -- npx -y mcdev-mcp serve
```

If you are developing from a local checkout, build first and point Codex at the local server:

```bash
git clone https://github.com/use-ai-for-mc/mcdev-mcp.git
cd mcdev-mcp
npm install
npm run build
codex mcp add mcdev-mcp -- node "$(pwd)/dist/index.js"
```

Verify that Codex can see it:

```bash
codex mcp list
codex mcp get mcdev-mcp
```

Restart Codex Desktop, or start a new Codex session, after adding the server. Codex will launch the MCP server automatically when a session needs it; you do not run `serve` by hand.

#### Other MCP clients

```json
{
  "mcpServers": {
    "mcdev": {
      "command": "npx",
      "args": ["-y", "mcdev-mcp", "serve"]
    }
  }
}
```

The `serve` subcommand starts the MCP server over stdio. Your MCP client (Claude Desktop, Cursor, etc.) launches it automatically — you never run `serve` directly.

### 3. (Optional) Install DebugBridge for live-game tools

The static analysis tools (`mc_search`, `mc_get_class`, `mc_find_refs`, …) work as soon as `init` finishes. The runtime tools (`mc_execute`, `mc_snapshot`, screenshots, world introspection, item textures, glow markers, etc.) additionally require the [DebugBridge mod](https://github.com/use-ai-for-mc/debugbridge) installed in the Minecraft instance you want to drive. Without DebugBridge, those tools will just report a connection error — the static half keeps working unaffected.

### Supported Versions

| Version Type | Example | Notes |
|--------------|---------|-------|
| New scheme (`26.x` and later) | `26.1`, `26.1-snapshot-10` | Recommended. Ships pre-unobfuscated; no ProGuard mapping step. |
| Releases `1.14` – `1.21.x` | `1.21.11`, `1.20.4`, `1.19.4` | Supported. Mojang's official ProGuard mappings are required (downloaded automatically). |
| Older releases (`< 1.14`) | `1.13`, `1.12.2` | **Not supported** — no official mappings published. |

The validator lives in [`src/cli.ts`](src/cli.ts) (`isValidVersion`). For 1.x.x releases it requires `1.14` or later; the new `26.x+` scheme is accepted unconditionally.

### (Optional) Skip Call Graph

```bash
# Skip callgraph generation if you don't need mc_find_refs
npx mcdev-mcp init -v 1.21.11 --skip-callgraph

# Generate callgraph later
npx mcdev-mcp callgraph -v 1.21.11
```

### Verify Installation

```bash
npx mcdev-mcp status
```

> **Note:** `mc_version` (with `action: "set"`) must be called before using any other static MCP tools. If the version isn't initialized, the AI will be instructed to ask you to run `init`.

### Install from source (development)

```bash
git clone https://github.com/use-ai-for-mc/mcdev-mcp.git
cd mcdev-mcp
npm install
npm run build

# Use the local build instead of npx
node dist/cli.js init -v 1.21.11
node dist/cli.js serve         # stdio MCP server; MCP clients launch this
```

> **Upgrading from an older version?** If you have a previous installation using DecompilerMC, run `npx mcdev-mcp clean --all` first to remove old cached data.

## MCP Tools

### Version Management (Static Tools)

Before using static tools, set the active Minecraft version:

### `mc_version`
Manage the active Minecraft version. Call with `action: "set"` before other static tools, or `action: "list"` to see what's initialized.

```json
{
  "action": "set",
  "version": "1.21.11"
}
```

```json
{
  "action": "list"
}
```

### Static Tool Requirements

| Tool | Requires `init` | Requires `callgraph` |
|------|-----------------|---------------------|
| `mc_version` | - | - |
| `mc_search` | ✓ | - |
| `mc_get_class` | ✓ | - |
| `mc_get_method` | ✓ | - |
| `mc_list_classes` | ✓ | - |
| `mc_list_packages` | ✓ | - |
| `mc_find_hierarchy` | ✓ | - |
| `mc_find_refs` | ✓ | ✓ |

### `mc_search`
Search decompiled source code for classes, methods, or fields by name pattern.

```json
{
  "query": "Minecraft",
  "type": "class"
}
```

### `mc_get_class`
Get the full decompiled source code for a class.

```json
{
  "className": "net.minecraft.client.Minecraft"
}
```

### `mc_get_method`
Get source code for a specific method with context.

```json
{
  "className": "net.minecraft.client.Minecraft",
  "methodName": "tick"
}
```

### `mc_find_refs`
Find who calls a method (callers) or what it calls (callees).

```json
{
  "className": "net.minecraft.client.MouseHandler",
  "methodName": "setup",
  "direction": "callers"
}
```

| Direction | Description |
|-----------|-------------|
| `callers` | Find methods that call this method |
| `callees` | Find methods this method calls |

> **Note:** Requires callgraph to be generated (included in `init` by default).

### `mc_list_classes`
List all classes under a specific package path (includes subpackages).

```json
{
  "packagePath": "net.minecraft.client.gui.screens"
}
```

### `mc_list_packages`
List all available packages. Optionally filter by namespace.

```json
{
  "namespace": "minecraft"
}
```

| Namespace | Description |
|-----------|-------------|
| `minecraft` | Minecraft client classes |
| `fabric` | Fabric API classes (if indexed) |

### `mc_find_hierarchy`
Find classes that extend or implement a given class or interface.

```json
{
  "className": "net.minecraft.world.entity.Entity",
  "direction": "subclasses"
}
```

| Direction | Description |
|-----------|-------------|
| `subclasses` | Classes that extend this class |
| `implementors` | Classes that implement this interface |

---

### Runtime Tools

These tools require Minecraft to be running with the [DebugBridge](https://github.com/use-ai-for-mc/debugbridge) mod installed.

### `mc_connect`
Connect to a running Minecraft instance. Other runtime tools auto-connect if needed. Pass `reset: true` to disconnect and clear state before reconnecting (useful when switching instances). If `port` is omitted, scans ports 9876-9886.

```json
{
  "port": 9876,
  "reset": false
}
```

### `mc_execute`
Execute Groovy code in the running game. The binding persists across calls,
and `mc` / `player` / `level` are pre-bound. (The runtime migrated from Lua
to Apache Groovy 5 in mid-2026 — the tool description carries a Lua→Groovy
cheat-sheet.)

```groovy
return player.blockPosition().toShortString()
```

### `mc_snapshot`
Get a structured snapshot of current game state (player, world, time, weather).

```json
{}
```

### `mc_screenshot`
Capture the game window as a JPEG file and return its path.

```json
{
  "downscale": 2,
  "quality": 0.75
}
```

### `mc_record_video`
Capture a short burst of frames for debugging temporal rendering issues (animation glitches, shader bugs, particles, sub-tick artifacts a single screenshot can't resolve). Returns either one composed grid JPEG (default) or N separate frame JPEGs.

```json
{
  "frames": 60,
  "interval": 50,
  "output": "grid",
  "downscale": 2,
  "quality": 0.75
}
```

`interval` is either `"frame"` (every render tick, ~60 Hz) or milliseconds (number, >= 1). Numeric intervals (50–100 ms) are recommended unless you specifically need sub-tick detail; at `"frame"` cadence the encoder may fall behind and the response's `dropped` count tells you how many frames were skipped. Capped at 300 frames per call. Files land under `<gameDir>/debugbridge-recordings/<requestId>/`.

### `mc_screen_inspect`
Snapshot the screen the player currently has open (chest UI, inventory, advancement screen, etc.) and return its structure.

```json
{
  "includeIcons": false
}
```

Set `includeIcons: true` to render each unique item in the screen as a small PNG and attach an icons map keyed by registry id.

### `mc_chat_history`
Get the most recent client-side chat messages — what the user has seen in chat.

```json
{
  "limit": 50,
  "includeJson": false
}
```

Set `includeJson: true` to include each message's full Minecraft `Component` JSON (useful when chat-message styling matters).

### `mc_nearby_entities`
List entities (mobs, items, projectiles, players) within a radius of the player.

```json
{
  "range": 64,
  "limit": 100,
  "includeIcons": false
}
```

Returns each entity's id, type, position, and primary equipment summary. Pass the `id` to `mc_entity_details`, `mc_set_entity_glow`, or `mc_get_entity_item_texture` to drill in.

### `mc_entity_details`
Get full details for one entity by id (the `id` field returned by `mc_nearby_entities` or `mc_looked_at_entity`).

```json
{
  "entityId": 12345
}
```

### `mc_looked_at_entity`
Returns the entity id the player is currently aiming at (raycast), or `null` if nothing is in the line of sight.

```json
{
  "range": 64
}
```

### `mc_nearby_blocks`
List nearby block-entities (signs, chests, banners, beacons, hoppers, …). Plain world blocks aren't included — use `mc_block_details` for any specific position.

```json
{
  "range": 16,
  "limit": 100
}
```

### `mc_block_details`
Get details for the block-entity at `(x, y, z)`: sign lines, chest contents, banner patterns, etc.

```json
{
  "x": 100,
  "y": 64,
  "z": 200
}
```

### `mc_set_entity_glow`
Outline an entity with the team-colour glow so the user can spot it. Pass `glow: false` to remove.

```json
{
  "entityId": 12345,
  "glow": true
}
```

### `mc_set_block_glow`
Highlight a block in the world (yellow outline on 1.19, vanilla glow on newer versions). Pass `glow: false` to remove just this position.

```json
{
  "x": 100,
  "y": 64,
  "z": 200,
  "glow": true
}
```

### `mc_clear_block_glow`
Clear all block highlights set via `mc_set_block_glow` in one call.

```json
{}
```

### `mc_get_item_texture`
Render the item in the player's inventory slot N as a PNG attached as MCP image content.

```json
{
  "slot": 0
}
```

| Slot range | Meaning |
|---|---|
| 0–35 | Main inventory (0–8 are the hotbar) |
| 36–39 | Armor (boots, leggings, chestplate, helmet) |
| 40 | Off-hand |

### `mc_get_item_texture_by_id`
Render the default texture for a registry id (e.g. `minecraft:diamond`) without needing the item to be in any inventory.

```json
{
  "itemId": "minecraft:diamond"
}
```

### `mc_get_entity_item_texture`
Render an item carried by another entity. `slot` is `"mainhand"`, `"offhand"`, or one of the armor slot names.

```json
{
  "entityId": 12345,
  "slot": "mainhand"
}
```

### Session control & dev loop

These five tools are the bridge-side primitives of the rebuild → relaunch → rejoin loop. The underlying endpoints (`disconnect`, `joinServer`, `quit`) are **disabled by default**: set `"session_control_enabled": true` in `<minecraft>/config/debugbridge.json` and restart the client (the flag is read at startup). `mc_connect` reports whether the connected instance has it enabled, and the tools return exact instructions when it's off.

The machine-specific halves of the loop — building the mod, copying the jar into `<gameDir>/mods/`, and launching the client — are deliberately **not** server tools: a coding agent with shell access discovers and runs them itself, guided by the [`mcdev://guides/dev-loop` resource](resources/dev-loop.md) (also available as a copyable Claude Code skill in [`skills/minecraft-dev-loop/`](skills/minecraft-dev-loop/SKILL.md)). The short version: the agent derives the deploy target, instance name, and launcher from the `gameDir` that `mc_connect` reports, persists the launch command it composes in the project's CLAUDE.md, and leaves authentication entirely to the launcher.

> **Caution:** `mc_quit_client` shuts down the whole Minecraft client, and `mc_join_server` / `mc_leave_server` change which world the user is in — they tear down the current play session. For repeated automated test runs, prefer a local throwaway server over a live community server (nondeterministic world, other players, server rules).

### `mc_join_server`
Join a multiplayer server (disconnecting from the current world first if needed). The server resource pack is pre-accepted by default so the join doesn't stall on the confirmation prompt. By default polls every second until a game snapshot shows a player (joined) or a `DisconnectedScreen` appears (failed — its title is returned as the reason).

```json
{
  "address": "localhost:25565",
  "acceptResourcePacks": true,
  "wait": true,
  "timeoutSeconds": 60
}
```

### `mc_leave_server`
Leave the current world/server to the title screen (when not in a world, it still resets the open menu screen to the title screen). Fire-and-acknowledge — the ack means the disconnect was queued on the game thread.

```json
{}
```

### `mc_wait_until_in_world`
Poll until the player is in a world, a `DisconnectedScreen` appears, or the timeout elapses. Read-only (doesn't require session control); useful after `mc_join_server` with `wait: false` or after a relaunch.

```json
{
  "timeoutSeconds": 60
}
```

### `mc_quit_client`
Gracefully shut down the Minecraft client (the WebSocket dropping right after the ack is the normal success mode). By default polls until the bridge port stops listening, so success means the process is actually gone and it's safe to relaunch.

```json
{
  "waitForExit": true,
  "timeoutSeconds": 30
}
```

### `mc_wait_for_bridge`
Block until a freshly (re)launched client's bridge answers, then connect to it. Sweeps ports 9876-9886 once per second, only accepting the instance that matches the previous connection's game directory / version — so a second running instance isn't mistaken for the relaunch. Pass `expectedVersion` only when deliberately switching instances. Read-only.

```json
{
  "expectedVersion": "1.21.11",
  "timeoutSeconds": 120
}
```

### `mc_run_command` *(opt-in dev tool)*
Execute a Minecraft slash command.

```json
{
  "command": "/give @s minecraft:diamond 64"
}
```

> **Disabled by default.** Both this server (`MCDEV_RUN_COMMAND=1`) and the DebugBridge mod (`runCommandEnabled` in `BridgeConfig`) must opt-in. See [Opt-in / dev tools](#opt-in--dev-tools) below.

### `mc_script_logs` *(opt-in dev tool)*
Review the file-backed log of past `mc_execute` runs (timestamp, code, result, error, duration), summarise common error patterns, or print the log paths.

```json
{
  "mode": "errors",
  "limit": 20
}
```

| Mode | Returns |
|---|---|
| `"errors"` | The most recent failed `mc_execute` calls |
| `"stats"` | Aggregate error patterns (which messages recur) |
| `"paths"` | Where the log files live on disk |

> **Disabled by default.** Enabled by `MCDEV_SCRIPT_LOGS=1`. The Claude Desktop MCPB exposes this as a user-facing toggle ("Log script executions") — see [Opt-in / dev tools](#opt-in--dev-tools).

## Opt-in / dev tools

Two runtime tools are gated behind environment variables so the default server only exposes the read-only and "safe" wrappers. The bridge mod has its own matching flags, so flipping just the server-side env on does nothing if the mod hasn't also opted in.

| Tool | Env var | Bridge-side flag | Surface in Claude Desktop |
|---|---|---|---|
| `mc_run_command` | `MCDEV_RUN_COMMAND=1` (or `true`) | `runCommandEnabled` | Not exposed via the MCPB user_config — set the env explicitly when launching the server. |
| `mc_script_logs` | `MCDEV_SCRIPT_LOGS=1` (or `true`) | (server-side only) | "Log script executions" toggle in the MCPB extension settings (also enables file logging of every `mc_execute`). |

When the env var is unset (or set to `0`/`false`), the tool simply isn't registered and won't appear in the MCP client's tool list.

### AST-based Java indexer (preview)

Set `MCDEV_AST_PARSER=1` before `init` or `rebuild` to use the new [java-parser](https://www.npmjs.com/package/java-parser)-backed indexer. Compared to the default regex parser it:

- Correctly handles multi-line annotations, nested generics, records, sealed types, pattern matching, and lambda-initialised fields (the regex parser silently miscounts on each of these).
- Picks up interface constants and `default`/`static` interface methods that the regex parser misses.
- Does not fold nested-class members into the outer type's lists.

In a head-to-head on Minecraft 1.21.11 source (500-file sample), the AST parser found **~2× more fields** and **~33% fewer (correctly attributed) methods** than the regex parser. It is ~4.5× slower per file, so a full re-index runs in roughly **75 seconds** instead of 17 — acceptable inside an `init` that already takes 2–5 minutes for download + decompile.

```bash
MCDEV_AST_PARSER=1 npx mcdev-mcp init -v 1.21.11
# or, to re-index an already-decompiled version:
MCDEV_AST_PARSER=1 npx mcdev-mcp rebuild -v 1.21.11 --with-callgraph
```

The MCP server stamps `manifest.indexerVersion` so it can tell which parser produced the existing index. When you flip the flag but haven't rebuilt yet, the server prints a one-time hint per version on the next tool call:

```
[source-store/manifest:1.21.11] Index was built with the 'regex' parser, but the server is now running the 'ast' parser.
  This is fine — existing indices still work — but the new parser would produce a better index.
  Run `mcdev-mcp rebuild -v 1.21.11` (or `init -v 1.21.11` for a full re-fetch) to refresh.
  Set MCDEV_SUPPRESS_INDEXER_HINT=1 to silence this message.
```

## Requirements

| Dependency | Version | Purpose |
|------------|---------|---------|
| Node.js | 18+ | Runtime |
| Java | 8+ | Decompilation (Vineflower) & callgraph |
| ~2GB | disk | Decompiled sources + cache |

> **Note:** Java 17+ is recommended for the `callgraph` command due to Gradle compatibility.

## CLI Commands

Invoke via `npx mcdev-mcp <command>` (or `node dist/cli.js <command>` from a source checkout).

| Command | Description |
|---------|-------------|
| `serve` | Start the MCP server over stdio (launched by MCP clients — not run by humans) |
| `init -v <version>` | Download, decompile, index Minecraft sources, and generate callgraph |
| `init -v <version> --skip-callgraph` | Same as above but skip callgraph generation |
| `callgraph -v <version>` | Generate call graph for `mc_find_refs` |
| `status` | Show all initialized versions and what stage each one is at |
| `rebuild -v <version>` | Rebuild the symbol index from already-cached sources |
| `rebuild -v <version> --with-callgraph` | Also regenerate the callgraph in the same run |
| `clean -v <version> --all` | Remove cached data for one version |
| `clean --all` | Remove all cached data across versions |

### Re-indexing

To re-index a version:

```bash
# Clean existing data for a version
npx mcdev-mcp clean -v 1.21.11 --all

# Re-initialize
npx mcdev-mcp init -v 1.21.11
```

## Architecture

```
mcdev-mcp/
├── src/
│   ├── index.ts              # MCP server entry point
│   ├── cli.ts                # CLI commands
│   ├── tools/
│   │   ├── static/           # Decompiled source tools
│   │   └── runtime/          # DebugBridge runtime tools
│   ├── decompiler/           # Vineflower integration
│   ├── indexer/              # Symbol index builder
│   ├── callgraph/            # Call graph generation & queries
│   └── storage/              # Source & index storage
└── dist/                     # Compiled output
```

### How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                     MCP Client (AI Agent)                    │
└─────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┴────────────────────┐
         ▼                                          ▼
┌─────────────────────────────┐    ┌──────────────────────────────────┐
│   Static Tools (8)          │    │   Runtime Tools (18 + 2 opt-in)  │
│  ┌────────────────────────┐ │    │  ┌────────────────────────────┐  │
│  │ mc_version             │ │    │  │ mc_connect / mc_execute    │  │
│  │ mc_search              │ │    │  │ mc_snapshot / mc_screenshot│  │
│  │ mc_get_class / method  │ │    │  │ mc_screen_inspect          │  │
│  │ mc_list_classes / pkgs │ │    │  │ mc_chat_history            │  │
│  │ mc_find_hierarchy      │ │    │  │ mc_nearby_entities + det.  │  │
│  │ mc_find_refs           │ │    │  │ mc_nearby_blocks   + det.  │  │
│  └───────────┬────────────┘ │    │  │ mc_looked_at_entity        │  │
│              │              │    │  │ mc_set_*_glow / mc_clear_* │  │
│       ┌──────┴──────┐       │    │  │ mc_get_item_texture (×3)   │  │
│       ▼             ▼       │    │  │ ─── opt-in (env-gated) ─── │  │
│  ┌─────────┐  ┌──────────┐  │    │  │ mc_run_command             │  │
│  │  Index  │  │Callgraph │  │    │  │ mc_script_logs             │  │
│  │ (JSON)  │  │ (SQLite) │  │    │  └─────────────┬──────────────┘  │
│  └────┬────┘  └────┬─────┘  │    │                │                 │
└───────┼────────────┼────────┘    │         ┌──────┴──────┐          │
        ▼            ▼              │         ▼             │          │
┌────────────────────────────┐      │   ┌──────────────┐    │          │
│ Decompiled Src (local)     │      │   │  WebSocket   │    │          │
│ (Vineflower)               │      │   │ to Minecraft │    │          │
└────────────────────────────┘      │   └──────┬───────┘    │          │
                                    └──────────┼────────────┘
                                               ▼
                                    ┌────────────────────────────┐
                                    │ DebugBridge Mod (in game)  │
                                    │ github.com/use-ai-for-mc/  │
                                    │ debugbridge                │
                                    └────────────────────────────┘
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed design documentation.

## Storage location

`mcdev-mcp` stores all cached data in the OS-standard cache directory, courtesy of [`env-paths`](https://github.com/sindresorhus/env-paths). Everything under this directory is **regeneratable** — safe to delete at any time — and `init` will rebuild what it needs on next run.

| Platform | Path |
|---|---|
| macOS   | `~/Library/Caches/mcdev-mcp` |
| Linux   | `~/.cache/mcdev-mcp` (XDG-compliant, honours `$XDG_CACHE_HOME`) |
| Windows | `%LOCALAPPDATA%\mcdev-mcp\Cache` |

Disk usage: approximately **2 GB per Minecraft version** (JAR ~60 MB, decompiled sources ~1.8 GB, callgraph DB ~200 MB, symbol index ~50 MB). Run `npx mcdev-mcp status` to see which versions are cached, and `npx mcdev-mcp clean --all` (or `clean -v <version> --all`) to reclaim space.

### Layout

```
<cache-dir>/
├── tools/
│   └── vineflower.jar         # Decompiler, downloaded once
├── java-callgraph2/           # Call graph tool, cloned once
├── cache/
│   └── {version}/
│       ├── jars/               # Downloaded Minecraft client JARs
│       └── client/             # Decompiled Minecraft sources
├── index/
│   └── {version}/
│       ├── manifest.json       # Index metadata
│       └── minecraft/          # Per-package symbol indices
└── tmp/                        # Temporary files (cleaned by --all)
```

> **Upgrading from a pre-1.0 install?** Earlier versions stored everything under `~/.mcdev-mcp/`. If you have data there and want to keep it, move it manually to the new location (e.g. on macOS: `mv ~/.mcdev-mcp ~/Library/Caches/mcdev-mcp`). Otherwise just run `init` again — the download step is idempotent.

## Development

```bash
npm run build    # Compile TypeScript
npm test         # Run tests
npm run lint     # Lint code
npm run mcpb     # Build a Claude Desktop MCPB bundle for the current platform
```

## Releasing

Releases are tag-driven. Pushing a `v*` tag triggers GitHub Actions to:

1. Run the full test matrix and TypeScript checks
2. Build a single universal MCPB bundle on `ubuntu-latest`
3. Publish the package to npm
4. Create a GitHub Release with the `.mcpb` attached

To cut a release:

```bash
# 1. Bump the version. npm version only touches package.json; mirror the same
#    value into manifest.json by hand — the verify-version CI job hard-fails
#    if the two disagree with the tag.
npm version patch          # or: minor, major, 1.2.3, etc.
$EDITOR manifest.json      # set "version" to match package.json

# 2. Commit the manifest bump (npm version already committed package.json)
git commit -am "Sync manifest.json version"
git tag -f "v$(node -p 'require(\"./package.json\").version')"

# 3. Push the commit and the tag
git push --follow-tags
```

That's it — the workflow at `.github/workflows/ci.yml` handles the rest. No `NPM_TOKEN` secret is needed; the workflow publishes to npm via [**Trusted Publishing**](https://docs.npmjs.com/trusted-publishers) (OIDC). A trusted publisher must be configured on the npm side, under the package's publishing-access settings: owner `use-ai-for-mc`, repo `mcdev-mcp`, workflow `ci.yml`. Releases also ship npm provenance attestations via `npm publish --provenance`.

The MCPB build is also runnable locally:

```bash
npm run mcpb
# → dist-mcpb/mcdev-mcp-<version>.mcpb
```

The bundle is universal — pure JavaScript plus [`sql.js`](https://github.com/sql-js/sql.js) (SQLite compiled to WebAssembly), no native binaries. The same `.mcpb` works on macOS (arm64 and x86_64), Linux (x64/arm64), and Windows. Node ≥ 20 is required at runtime (from `package.json` `engines`).

### Installing the MCPB in Claude Desktop

Download the bundle from the [Releases page](https://github.com/use-ai-for-mc/mcdev-mcp/releases) and double-click the `.mcpb` file. Claude Desktop will validate the manifest and offer to install it. After install, run `mcdev-mcp init -v <version>` in a terminal once to populate the cache (the extension cannot trigger `init` itself — it's deliberately terminal-only, see [Quick Start](#quick-start)).

## Limitations

- **Static Analysis**: `mc_find_refs` cannot trace calls through reflection, JNI callbacks, or lambda/method references created dynamically
- **Client Only**: Server-side classes are not included in static analysis
- **Runtime Tools**: Require Minecraft running with the [DebugBridge](https://github.com/use-ai-for-mc/debugbridge) mod installed

## Legal Notice

This tool decompiles Minecraft source code for development reference purposes. Please respect Mojang's intellectual property:

**You MAY:**
- Decompile and study the code for understanding and learning
- Use the knowledge to develop mods that don't contain substantial Mojang code
- Reference class/method names for mod development

**You may NOT:**
- Distribute decompiled source code
- Distribute modified versions of Minecraft
- Use decompiled code commercially without permission

Per the [Minecraft EULA](https://www.minecraft.net/eula): *"You may not distribute any Modded Versions of our game or software"* and *"Mods are okay to distribute; hacked versions or Modded Versions of the game client or server software are not okay to distribute."*

This tool is for **reference only** — do not copy decompiled code directly into your projects.

## Third-Party Components

This project includes or uses third-party software under the following licenses:

- **[DecompilerMC](https://github.com/hube12/DecompilerMC)** (MIT) — Decompiler logic adapted and translated from Python to TypeScript in `src/decompiler/`
- **[Vineflower](https://github.com/Vineflower/vineflower)** (Apache-2.0) — Java decompiler used for source generation
- **[java-callgraph2](https://github.com/Adrninistrator/java-callgraph2)** — Cloned at runtime for static call graph generation

Additional runtime dependencies (downloaded/used):
- **[Mojang](https://www.minecraft.net/)** — Official ProGuard mappings and Minecraft client JAR

See [LICENSE](LICENSE) for full license text and third-party attributions.

## License

[MIT](LICENSE) — Copyright (c) 2025 mcdev-mcp contributors
