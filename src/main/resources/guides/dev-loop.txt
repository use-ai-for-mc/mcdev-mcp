# The mod dev loop: build → deploy → relaunch → rejoin

This guide is for a coding agent with **shell access** plus the mcdev-mcp tools. It describes how to test mod changes in
the running game without human interaction: rebuild the mod, deploy it, restart the Minecraft client, and get back into
a world.

The split of responsibilities is deliberate:

- **You (the agent, via your shell)**: build, deploy the jar, and launch the client. These are machine-specific —
  launcher location, instance name, build system — and you are equipped to discover and adapt to them.
- **mcdev-mcp tools**: everything that speaks the DebugBridge WebSocket (`mc_quit_client`, `mc_join_server`,
  `mc_leave_server`) and the blocking waits that would otherwise cost you a tool call per second of polling
  (`mc_wait_for_bridge`, `mc_wait_until_in_world`).

> **Caution:** quitting or relaunching tears down the user's entire play
> session, and joining a server changes which world they're in. Only run this
> loop when the user asked for it. For repeated automated test runs, prefer a
> local throwaway server over a live community server — live servers have
> nondeterministic worlds, other players, and rules about automation.

## Step 0 — capability check

The bridge's session-control endpoints (`disconnect`, `joinServer`, `quit`)
are **disabled by default**. Call `mc_connect` and look for
`Session control: enabled`. If it's disabled, stop and tell the user to edit
`<gameDir>/config/debugbridge.json`, set `"session_control_enabled": true`, and restart the client once (the flag is
only read at startup). The gated tools return these exact instructions too.

## Step 1 — one-time discovery (per machine)

Do this once while the client is **still running**, then persist what you find (in the project's CLAUDE.md or your
equivalent memory) so future sessions skip straight to step 2.

`mc_connect` gives you `gameDir` — the instance's run directory — and the Minecraft version. From `gameDir` you can
derive nearly everything:

- **Deploy target**: `<gameDir>/mods/` is where the built jar goes.
- **Instance name**: for Prism/MultiMC-style launchers the path looks like
  `…/PrismLauncher/instances/<instance>/.minecraft` (or `…/minecraft` on some setups) — the instance name is right
  there, and confirmed by the
  `name=` line in `instances/<instance>/instance.cfg`.
- **Launcher**: the same path tells you which launcher manages it. Find the binary with `which prismlauncher`,
  `flatpak list --app | grep -i prism`, checking `~/Library/Application Support/PrismLauncher` (macOS) or
  `%APPDATA%\PrismLauncher` (Windows), etc.

Compose and record a launch command. Working examples:

| Setup                | Command                                                                                 |
|----------------------|-----------------------------------------------------------------------------------------|
| Linux, native Prism  | `prismlauncher --launch "<instance>"`                                                   |
| Linux, Flatpak Prism | `flatpak run org.prismlauncher.PrismLauncher --launch "<instance>"`                     |
| macOS                | `"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch "<instance>"` |
| Windows              | `start "" "C:\…\prismlauncher.exe" --launch "<instance>"`                               |

On macOS, call the binary inside the app bundle directly. The tempting
`open -a "Prism Launcher" --args --launch "<instance>"` only forwards
`--args` when the app isn't already running — and Prism usually *is*
(it stays open behind the game), making the relaunch a silent no-op. The direct binary forwards the launch to the
running Prism instance instead.

If the instance is run by the **official Minecraft launcher** (`gameDir` is
`~/.minecraft` with no instance directory), there is no reliable CLI launch — use the manual fallback in step 4 instead.

**Authentication is the launcher's job, never yours.** The user must have logged into the launcher GUI once on this
machine; after that the launcher refreshes the Microsoft token silently on every launch. Do not attempt to obtain,
store, or pass tokens yourself.

## Step 2 — build & deploy

Use the mod repo's own build (`./gradlew build`, or its build-and-deploy script if it has one). If you deploy by hand,
copy the built jar from
`build/libs/` into `<gameDir>/mods/` and remove the *previous version of the same mod only* — match on the mod's jar
name prefix; never clear the whole mods folder. Deploy while the client is still up or after quitting; the jar is only
read at launch.

## Step 3 — quit the client

`mc_quit_client`. It tolerates the WebSocket dropping mid-call (that's the normal shutdown signal) and by default waits
until the client is truly gone:
it resolves the PID listening on the bridge port before quitting, then polls until the port stops listening and that
process exits. On success you can relaunch immediately — including through launchers that track the instance (Prism
silently ignores `--launch` while the old process lives). Read the result text for the two degraded cases:

- **PID couldn't be resolved** (no `lsof`, permissions): the tool falls back to port-close-only and says so. The JVM can
  outlive the port by a few seconds — wait for the old process to actually exit (`pgrep -f` /
  `kill -0`) before relaunching.
- **Timeout**: if the port is still open, the game is probably stuck on a save/exit dialog — ask the user. If the port
  closed but the process is still running, the JVM is hung finishing shutdown; check it again before relaunching.

## Step 4 — launch, detached

Run the recorded launch command **detached from your shell**, or the game dies when your command's process group is
cleaned up:

```bash
setsid prismlauncher --launch "<instance>" >/dev/null 2>&1 < /dev/null &
```

(`nohup … &` also works; macOS `open -a` and Windows `start` detach naturally.)

**Manual fallback:** if no programmatic launch works on this machine (official launcher, login trouble, sandboxing),
just ask the user to start the client themselves and continue with step 5 — the loop still works with one manual click.

## Step 5 — wait for the bridge

`mc_wait_for_bridge` (default timeout 120s). It sweeps ports 9876–9886 every second and only accepts the instance
matching the previous connection's game directory / version — important when the user runs two instances (e.g. 1.21.11
and 1.19) side by side, and necessary because the relaunched client may come up on a *different* port in the range. Pass
`expectedVersion` only when deliberately switching instances.

On timeout, in order of likelihood:

1. **Launcher login prompt** — the Microsoft token expired (~90 days idle). Tell the user to log into the launcher GUI
   once, then retry.
2. **Crash during startup** — often the freshly deployed mod. Read
   `<gameDir>/logs/latest.log` with your file tools.
3. **Wrong/garbled launch command** — check the launcher's own logs, or whether the process is even running
   (`pgrep -f`).

## Step 6 — rejoin and verify

`mc_join_server` with the server address (it pre-accepts the server resource pack so the join doesn't stall on the
confirmation prompt, and by default polls until you're in-world). A failure returns the DisconnectedScreen title as the
reason — `"Failed to verify username"`-style messages mean the launcher login is stale (see above). For slow joins,
`mc_wait_until_in_world`
continues waiting without re-sending the join.

The bridge port opens well before the client finishes its startup resource reload, so a join fired the moment step 5
succeeds used to race that reload:
the server resource pack's own reload collided with it ("Reload already ongoing, replacing" in the log) and the pack
silently never applied. Current bridges absorb this — `joinServer` waits for the title screen to settle before
connecting and only then acks, so expect the ack itself to take some extra seconds right after a launch. On older bridge
jars, wait for the title screen yourself (e.g. `mc_screen_inspect` showing TitleScreen) before joining a server whose
gameplay depends on its resource pack.

Once in-world, verify your change with the runtime tools (`mc_screenshot`,
`mc_execute`, `mc_snapshot`, …).

## Test servers

For automated iteration, run a **local throwaway server** (Paper/Fabric) as a detached process you manage yourself. Set
`online-mode=false` in its
`server.properties` — that also makes joins independent of launcher authentication entirely. Remember the eula file, and
shut the server down when the session's done.
