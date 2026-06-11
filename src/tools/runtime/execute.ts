import { bridgeSession } from "./session.js";
import { scriptLogger } from "./script-logger.js";
import { isEnvOn } from "../../utils/env.js";

const scriptLogsEnabled = isEnvOn('MCDEV_SCRIPT_LOGS');

export const mcExecuteTool = {
    name: "mc_execute",
    description: `Execute GROOVY code in the Minecraft session (the runtime migrated from Lua to Apache Groovy 5 in mid-2026 — see the migration note at the end if you knew the old surface). The binding is persistent: undeclared assignments (x = 5) survive to later calls; def x is script-local.

PREFER NATIVE TOOLS WHERE POSSIBLE — they're faster and avoid script overhead:
- Player state (x/y/z/yaw/pitch/look/velocity/vehicle/raycast target/world): mc_snapshot
- Nearby entities or one entity's details: mc_nearby_entities / mc_entity_details
- Nearby block entities (signs, chests, etc.): mc_nearby_blocks / mc_block_details
- Open screen / inventory contents: mc_screen_inspect
- Recent chat: mc_chat_history
- Item textures: mc_get_item_texture (by slot or by id)
Reach for mc_execute when you need to explore the Java API or do something
the native tools don't cover.

Pre-bound globals: mc (Minecraft instance), player, level — plus the "java" helper.

Mojang names everywhere, on every Minecraft version: obj.foo reads a field
(JavaBean getter fallback), obj.foo(args) calls a method. Overloads resolve by
argument types; decimal literals coerce to double/float params.

Minecraft classes can't be named directly on obfuscated builds — load them via
java.type: def Vec3 = java.type('net.minecraft.world.phys.Vec3'); construct with
Vec3(1, 2, 3) or Vec3.create(1, 2, 3). (Single-quote class names: double-quoted
GStrings interpolate the $ in inner-class names.)

The "java" helper provides:
- java.type(className) - class handle for statics + construction, by Mojang name
- java.list(x) - Java collection/array -> Groovy List (use for iteration)
- java.typeName(obj) - the Mojang class name
- java.isNull(obj) - null check
- java.ref(refId) - retrieve a stored object reference ($ref_N from results)
- sync { ... } - run the closure ON THE GAME THREAD in one hop. Use it to batch
  bulk loops (hundreds of entities/slots run in milliseconds instead of one
  thread-hop per call): sync { java.list(level.entitiesForRendering()).collect { java.typeName(it) } }

Reflection helpers for exploring API:
- java.describe(obj) - full dump: class, fields, methods, supers
- java.methods(obj, [filter]) - list methods (optional name filter)
- java.fields(obj, [filter]) - list fields (optional name filter)
- java.supers(obj) - class hierarchy and interfaces
- java.find(pattern, [scope]) - search mappings for classes/methods/fields

Plain JDK classes work natively (System.currentTimeMillis(), new File(path).text = "...").
Sandbox: Runtime / ProcessBuilder / java.net.* are blocked; file I/O is allowed.
Caveat: bridge-wrapped Minecraft objects don't auto-unwrap when passed to NATIVE
Java calls like new File(wrappedFile, name) — pass strings/primitives or unwrap
with wrapped.getTarget(). Bridge-dispatched calls (anything on mc/player/level or
a java.type class) unwrap arguments automatically.

Use "return <value>" to get a value back; println/print output is captured.
Returned Minecraft objects serialize as {className, ref, toString, fields} —
resume them later with java.ref(ref).

timeoutMs: optional per-call deadline in ms (default 10000, max 300000 = 5 min).
Bump it for bulk reflection or heavy file I/O — but prefer sync{} batching or a
native tool over raising the timeout.

MIGRATING FROM THE OLD LUA SURFACE (pre-2026-06): obj:method(args) -> obj.method(args);
java.import(name) -> java.type(name); java.new(Cls, args) -> Cls(args) or
Cls.create(args); java.iter/java.array -> java.list; java.typeof -> java.typeName;
java.cast - removed (dispatch walks the runtime hierarchy, no cast needed);
io.open(...) -> new File(...); os.time() -> System.currentTimeMillis();
print(x) -> println x; pcall -> try/catch; local x -> def x;
{a = 1} tables -> [a: 1] maps and [1, 2, 3] lists.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            code: {
                type: "string",
                description: "Groovy code to execute",
            },
            timeoutMs: {
                type: "integer",
                description: "Optional per-call execution deadline in milliseconds. Range 1000-300000, default 10000 (10s). Use a longer value for bulk reflection or heavy file I/O.",
                minimum: 1000,
                maximum: 300000,
            },
        },
        required: ["code"],
    },

    handler: async (args: { code: string; timeoutMs?: number }) => {
        const startTime = Date.now();
        try {
            // `timeoutMs` is intentionally passed to two different layers:
            //   * payload `{ code, timeoutMs }` — bounds the script's own
            //     execution inside the Minecraft JVM (the bridge mod uses
            //     this to interrupt runaway scripts).
            //   * 3rd-arg `timeoutMs` — bounds the WebSocket request from
            //     this side (BridgeSession adds a +5s grace and then a
            //     5-minute ceiling, see session.ts).
            // Both are needed: dropping the payload one would let runaway
            // scripts pin the game thread; dropping the 3rd-arg one would
            // let the wait pile up here even after the bridge gave up.
            const resp = await bridgeSession.send("execute", { code: args.code, timeoutMs: args.timeoutMs }, args.timeoutMs);
            const duration_ms = Date.now() - startTime;

            // Log the execution (dev mode only)
            if (scriptLogsEnabled) {
                scriptLogger.log({
                    timestamp: new Date().toISOString(),
                    success: resp.success,
                    code: args.code,
                    result: resp.result,
                    output: resp.output,
                    error: resp.error,
                    duration_ms,
                });

                // Periodically rotate logs
                if (Math.random() < 0.01) {
                    scriptLogger.rotateIfNeeded();
                }
            }

            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            let text = "";
            if (resp.output) text += resp.output + "\n";
            if (resp.result) text += JSON.stringify(resp.result, null, 2);
            return { content: [{ type: "text" as const, text: text.trim() || "(no output)" }] };
        } catch (e: unknown) {
            const duration_ms = Date.now() - startTime;
            const msg = e instanceof Error ? e.message : String(e);

            // Log connection/timeout errors too (dev mode only)
            if (scriptLogsEnabled) {
                scriptLogger.log({
                    timestamp: new Date().toISOString(),
                    success: false,
                    code: args.code,
                    error: msg,
                    duration_ms,
                });
            }

            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
