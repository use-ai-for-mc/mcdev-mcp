---
name: minecraft-dev-loop
description: Test Minecraft mod changes in the running game - rebuild the mod, deploy the jar, restart the client via DebugBridge, and rejoin a server. Use when asked to "test this in game", restart Minecraft after a build, or run the build-deploy-relaunch-rejoin loop. Requires the Java mcdev-mcp MCP server and a compatible DebugBridge mod with session_control_enabled.
---

# Minecraft mod dev loop

The full procedure lives in the Java server's `mcdev://guides/dev-loop`
resource. **Read it first** through MCP resource access. It is the single source
of truth; this file is only the trigger and outline.

The client configuration launches `java -jar <absolute-path-to-release.jar> serve`.
Java 26 is the minimum runtime. DebugBridge remains a separate,
Minecraft-version-specific Fabric mod; the server's fixture-backed protocol
baseline is DebugBridge 2.0.0.

The loop, in short:

1. **Check capability**: `mc_connect` must show `Session control: enabled`;
   otherwise relay the enable instructions it gives and stop.
2. **Discover once per machine** (while the client runs): `gameDir` from
   `mc_connect` gives the deploy target (`<gameDir>/mods/`), the instance
   name, and the launcher. Persist the launch command in the mod project's
   agent instructions.
3. **Build & deploy** with the mod repo's build system (your shell).
4. **Quit** with `mc_quit_client` (waits for the process to be really gone).
5. **Launch detached** from your shell (`setsid … &` / `open -a` / `start`),
   or ask the user to click the launcher if no CLI launch works here.
6. **Reconnect** with `mc_wait_for_bridge`, **rejoin** with `mc_join_server`,
   then verify the change with the runtime tools.

Cautions that always apply: this tears down the user's play session — only
run it when asked. Authentication belongs to the launcher (user logs in once;
never handle tokens). Prefer a local throwaway server (`online-mode=false`)
over live community servers for automated runs.
