# Talking to the Minecraft backend from Python

Use this guide when you, as a coding agent, want to write a **Python script** that
drives a live Minecraft instance directly — bypassing the `mcdev-mcp` MCP tools
and speaking to the DebugBridge mod yourself.

You will almost always prefer the MCP tools (`mc_execute`, `mc_snapshot`, etc.)
in conversational use. Reach for a Python client when you need something an
ad‑hoc tool call can't give you: a long‑running watcher, a batch experiment, a
script the user will run later, integration into a non‑MCP harness, etc.

## What the "backend" actually is

There is no Python runtime inside Minecraft. The MCP server, the Python client,
and any other client all talk to the same thing: the **DebugBridge mod**
(`github.com/use-ai-for-mc/debugbridge`) running inside the Minecraft JVM. It
exposes a small WebSocket protocol and evaluates **Groovy** on the game side
(the runtime migrated from Lua to Apache Groovy 5 in mid-2026 — adjust any
older snippets you may have seen; a migration cheat-sheet is at the end of
"What Groovy you can send" below).

So a Python "script that calls the Minecraft backend" is really:

```
Python  ──ws──►  DebugBridge mod (inside Minecraft JVM)  ──►  Groovy eval  ──►  Java API
```

Your Python code is the transport. The interesting work is still expressed in
Groovy snippets sent through the `execute` request type.

## Wire protocol

Current source of truth: `dev.mcdevmcp.bridge.BridgeSession`,
`dev.mcdevmcp.bridge.BridgeClient`, and the typed endpoint/payload records in
`dev.mcdevmcp.bridge`. The MCP adapters live in
`dev.mcdevmcp.tools.runtime`. Re-read those Java classes if anything below
looks stale.

- **Transport:** plain WebSocket, `ws://127.0.0.1:<port>`, no TLS, no auth. The
  bridge only binds to loopback.
- **Default port:** `9876`. The MCP server scans `9876..9886` because users
  routinely run multiple Minecraft instances. Honour `DEBUGBRIDGE_PORT` if it
  is set in your environment.
- **Framing:** one JSON object per WebSocket text frame. Requests and responses
  are correlated by an `id` field that you assign.

### Request

```json
{
  "id":   "req_1",
  "type": "execute" | "snapshot" | "screenshot" | "search" | "runCommand" | "status"
          | "disconnect" | "joinServer" | "quit" | ...,
  "payload": { ... }
}
```

Notes per type:

| `type`       | `payload`                                                                  | Returns in `result` |
|--------------|----------------------------------------------------------------------------|---------------------|
| `status`     | `{}`                                                                       | `SessionInfo` (version, mappingStatus, gameDir, logsDir, latestLog, …) |
| `execute`    | `{ "code": "<groovy>", "timeoutMs"?: <int 1000-300000> }`                      | Whatever the Groovy `return`s; `output` carries `println` lines |
| `snapshot`   | `{}` (player/world snapshot — see `mc_snapshot`)                            | snapshot JSON |
| `screenshot` | `{}` (returns base64 JPEG)                                                  | image payload |
| `search`     | `{ "pattern": "<str>" }`                                                    | mapping search results |
| `runCommand` | `{ "command": "/give @s diamond" }` — gated by `run_command_enabled` on the mod | command result |
| `disconnect` | `{}` — leave the current world/server (lands on the title screen)            | ack only |
| `joinServer` | `{ "address": "host[:port]", "acceptResourcePacks"?: bool (default true) }`  | ack `{status: "connecting"}` |
| `quit`       | `{}` — close the Minecraft client (the bridge dies with it)                  | ack only |

The table is not exhaustive — the mod also serves the native inspection types
the MCP tools use (`screenInspect`, `chatHistory`, `nearbyEntities`,
`entityDetails`, `nearbyBlocks`, `record_video`, …); see `BridgeServer.handleRequest`
in the DebugBridge repo for the full switch.

`runCommand` is opt-in on the mod side; expect `success: false` with an
`error` mentioning the flag if it is disabled. The three session-control types
are likewise gated by `session_control_enabled` in `debugbridge.json` (`status`
reports the capability as `sessionControlEnabled`). `disconnect` and `quit` are
fire-and-acknowledge: the ack means "queued on the game thread". `joinServer`
(bridge ≥ 2.0.0) instead defers the connect until the client has settled (no
startup/reload overlay) and acks once the connect attempt has *started* —
bounded bridge-side at 60s + 5s grace, so set your read deadline past that; a
never-settling client gets an error response, and a newer `joinServer`
supersedes a pending one. Older bridges ack `joinServer` on queue. Either way
an ack is not "in world": poll `snapshot` (player present = in world) and
`screenInspect` for the outcome.
Relaunching a closed client is necessarily external (e.g.
`prismlauncher --launch <instance>`), then re-scan the port range.

### Response

```json
{
  "id":      "req_1",
  "success": true,
  "result":  ...,         // arbitrary JSON; absent on errors
  "output":  "...",       // optional, println/print captures
  "error":   "..."        // only when success is false
}
```

The server may also push frames whose `id` does not match any outstanding
request (late replies, replies from before a reconnect). Drop them and move
on — that is what the Java `BridgeClient` does.

### Timeouts

`timeoutMs` on an `execute` payload bounds script execution **inside** the JVM.
You should also enforce a wall‑clock timeout on the WebSocket round‑trip on the
Python side (the Java runtime tools use bounded deadlines and cancellation).
Without both, a frozen game can hang your script indefinitely.

## Minimal Python client

`pip install websockets` and you're done — no other deps.

```python
# debugbridge_client.py
import asyncio, itertools, json, os, websockets

DEFAULT_PORT = int(os.environ.get("DEBUGBRIDGE_PORT", "9876"))

class BridgeError(RuntimeError):
    pass

class DebugBridge:
    def __init__(self, port: int = DEFAULT_PORT):
        self.port = port
        self.ws = None
        self._ids = itertools.count(1)
        self._pending: dict[str, asyncio.Future] = {}
        self._reader_task: asyncio.Task | None = None

    async def connect(self):
        # The MCP server scans 9876..9886; mirror that if the configured port is busy.
        last_err = None
        for port in range(self.port, self.port + 11):
            try:
                self.ws = await asyncio.wait_for(
                    websockets.connect(f"ws://127.0.0.1:{port}"),
                    timeout=2.0,
                )
                self.port = port
                break
            except (OSError, asyncio.TimeoutError) as e:
                last_err = e
        else:
            raise BridgeError(
                f"No DebugBridge on ports {self.port}-{self.port+10}: {last_err}"
            )
        self._reader_task = asyncio.create_task(self._reader())
        return await self.send("status", {})

    async def _reader(self):
        try:
            async for raw in self.ws:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                fut = self._pending.pop(msg.get("id"), None)
                if fut and not fut.done():
                    fut.set_result(msg)
        except websockets.ConnectionClosed:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(BridgeError("connection closed"))
            self._pending.clear()

    async def send(self, type_: str, payload: dict, timeout: float = 10.0):
        if self.ws is None:
            raise BridgeError("not connected — call connect() first")
        req_id = f"py_{next(self._ids)}"
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        await self.ws.send(json.dumps({"id": req_id, "type": type_, "payload": payload}))
        try:
            resp = await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            self._pending.pop(req_id, None)
            raise BridgeError(f"{type_} timed out after {timeout}s")
        if not resp.get("success"):
            raise BridgeError(resp.get("error") or "unknown bridge error")
        return resp

    async def execute(self, groovy: str, timeout_ms: int = 10_000):
        # The bridge bounds the script inside the JVM; we still set a slightly
        # larger wall-clock timeout on this side so a frozen JVM can't hang us.
        resp = await self.send(
            "execute",
            {"code": groovy, "timeoutMs": timeout_ms},
            timeout=(timeout_ms / 1000) + 5,
        )
        return resp.get("result"), resp.get("output", "")

    async def close(self):
        if self.ws is not None:
            await self.ws.close()
        if self._reader_task is not None:
            self._reader_task.cancel()

async def main():
    bridge = DebugBridge()
    info = await bridge.connect()
    print("session:", info["result"])

    result, output = await bridge.execute("""
        return [name: player.getName().getString(), y: player.getY()]
    """)
    print("groovy return:", result)
    if output: print("groovy println:", output)

    await bridge.close()

if __name__ == "__main__":
    asyncio.run(main())
```

This is intentionally ~70 lines: one socket, one reader task, response
correlation by id, port scan, timeouts. If you need more (auto-reconnect,
session-info verification across reconnects, etc.), follow `BridgeSession` and
`BridgeClient`; they implement port scanning, identity checks, request
correlation, cancellation, and reconnect behavior.

## What Groovy you can send

The Groovy environment exposed by the bridge is documented in the embedded
`mc_execute` tool metadata at `src/main/resources/mcp/tools.json`; the Java
handler is `dev.mcdevmcp.tools.runtime.McExecuteTool`. Highlights:

- `mc`, `player`, `level` are pre-bound; the binding persists across calls
  (`x = 5` survives, `def x` is script-local).
- Field access is `obj.fieldName` (JavaBean getter fallback); method calls are
  `obj.methodName(args)`. All names are **Mojang-mapped** regardless of
  Minecraft version; overloads resolve by argument types.
- Minecraft classes load via `java.type('net.minecraft.world.phys.Vec3')`
  (single-quote the name — `$` in inner-class names breaks GStrings);
  construct with `Vec3(1, 2, 3)` or `Vec3.create(1, 2, 3)`.
- `java.list(coll)` (iteration), `java.typeName(obj)`, `java.isNull(obj)`,
  `java.ref(id)` (resume a `$ref_N` from an earlier result).
- Reflection: `java.describe(obj)`, `java.methods(obj, filter?)`,
  `java.fields(obj, filter?)`, `java.supers(obj)`, `java.find(pattern, scope?)`.
- `sync { ... }` runs the closure on the game thread in one hop — wrap bulk
  loops in it (hundreds of entities in milliseconds instead of one
  thread-hop per wrapper call).
- Plain JDK classes work natively: `System.currentTimeMillis()`,
  `System.getenv(name)`, `new File(path).text = "..."` for file I/O.
  `Runtime`/`ProcessBuilder`/`java.net.*` are sandboxed off.
- Bridge-wrapped Minecraft objects don't auto-unwrap when passed to *native*
  Java calls (e.g. `new File(wrappedFile, name)` fails) — pass strings or
  unwrap with `wrapped.getTarget()`. Calls dispatched through `mc`/`player`/
  `level`/`java.type` classes unwrap arguments automatically.
- Return values serialize to JSON; Groovy lists/maps become arrays/objects,
  other Java objects become `{className, ref, toString, fields}`.

Migrating snippets from the pre-2026-06 **Lua** surface: `obj:method(args)` →
`obj.method(args)`; `java.import(name)` → `java.type(name)`;
`java.new(Cls, args)` → `Cls(args)`; `java.iter`/`java.array` → `java.list`;
`java.typeof` → `java.typeName`; `java.cast` → removed (dispatch walks the
runtime hierarchy); `io.open(...)` → `new File(...)`; `os.time()` →
`System.currentTimeMillis()`; `print(x)` → `println x`; `pcall` → try/catch;
`local x` → `def x`; `{a = 1}` → `[a: 1]`.

## Pitfalls

1. **Batch bulk loops in `sync { ... }`.** Outside it, every wrapper call hops
   to the game thread individually. And never loop from Python — write the
   loop in Groovy and `return` a flat list — one round trip, not N.
2. **The bridge only binds to loopback.** A remote Python client cannot reach
   it without an SSH tunnel.
3. **Connection drops on world reload.** Some Minecraft state changes close
   and reopen the WebSocket. Production scripts need reconnect logic. Use the
   `status` reply's `gameDir` to detect "different game instance now". The
   Java `BridgeSession` and `SessionControlSupport` apply the same identity
   check while reconnecting.
4. **`runCommand` and session control are dev-only.** `runCommand` needs both
   this MCP server (`MCDEV_RUN_COMMAND=1`) and the mod (`run_command_enabled`)
   to opt in; `disconnect`/`joinServer`/`quit` need `session_control_enabled`
   on the mod. A Python client cannot enable either remotely.
5. **There is no streaming response.** A long script either completes within
   `timeoutMs` and returns one JSON blob, or it dies. If you need progress,
   have the Groovy snippet append to a file and tail that file from Python.

## When NOT to write a Python client

Most of the time you should just call the MCP tools. The Python client is
worth the extra moving parts only if:

- You need to run unattended (cron, CI, headless test rig).
- You're integrating with a non‑MCP system (a notebook, a game server admin
  tool, a different agent harness).
- You're stress‑testing the bridge itself.

For anything you'd do interactively with the user, `mc_execute` + the native
inspection tools are faster, safer, and already handle reconnects.
