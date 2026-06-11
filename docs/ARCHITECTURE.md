# Architecture Overview

mcdev-mcp is an MCP (Model Context Protocol) server that gives AI coding agents two complementary surfaces: **static analysis** of decompiled Minecraft source code, and **runtime interaction** with a live Minecraft client through the [DebugBridge](https://github.com/use-ai-for-mc/debugbridge) mod.

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MCP Client (AI Agent)                         │
│                  Any MCP-compatible AI coding tool                   │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ MCP Protocol (JSON-RPC over stdio)
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         mcdev-mcp Server                             │
│                                                                      │
│   ┌──────────────────────────┐    ┌────────────────────────────┐   │
│   │  Static Tools (8)         │    │ Runtime Tools (18 + 2 dev) │   │
│   │  src/tools/static/        │    │ src/tools/runtime/         │   │
│   │  ┌─────────────────────┐  │    │  ┌──────────────────────┐  │   │
│   │  │ mc_version          │  │    │  │ mc_connect           │  │   │
│   │  │ mc_search           │  │    │  │ mc_execute (Groovy)  │  │   │
│   │  │ mc_get_class/method │  │    │  │ mc_snapshot          │  │   │
│   │  │ mc_list_classes/pkg │  │    │  │ mc_screenshot        │  │   │
│   │  │ mc_find_hierarchy   │  │    │  │ mc_screen_inspect    │  │   │
│   │  │ mc_find_refs        │  │    │  │ mc_chat_history      │  │   │
│   │  └──────────┬──────────┘  │    │  │ mc_nearby_*  / *_det │  │   │
│   │             │              │   │  │ mc_looked_at_entity  │  │   │
│   │             ▼              │   │  │ mc_*_glow / clear    │  │   │
│   │   ┌─────────────────┐      │   │  │ mc_get_item_texture* │  │   │
│   │   │  SourceStore    │      │   │  │ ── opt-in (env) ───  │  │   │
│   │   │ (source-store)  │      │   │  │ mc_run_command       │  │   │
│   │   └────────┬────────┘      │   │  │ mc_script_logs       │  │   │
│   │            │                │   │  └──────────┬───────────┘  │   │
│   │   ┌────────▼────────┐       │   │             │              │   │
│   │   │ CallgraphQuery  │       │   │  ┌──────────▼───────────┐  │   │
│   │   │    (query.ts)   │       │   │  │   BridgeSession      │  │   │
│   │   └────────┬────────┘       │   │  │   (session.ts)       │  │   │
│   └────────────┼────────────────┘   │  └──────────┬───────────┘  │   │
│                │                    └─────────────┼──────────────┘   │
│   ┌────────────▼────────────┐                     │                  │
│   │       Data Layer         │                     │                  │
│   │  ┌──────────┐ ┌────────┐ │                     │                  │
│   │  │ Versioned│ │Versioned│ │                    │                  │
│   │  │ Symbol   │ │Callgraph│ │                    │                  │
│   │  │ Index    │ │  DB     │ │                    │                  │
│   │  │ (JSON)   │ │(SQLite/ │ │                    │                  │
│   │  │          │ │ sql.js) │ │                    │                  │
│   │  └──────────┘ └─────────┘ │                    │                  │
│   └──────────────────────────┘                     │                  │
└────────────────────────────────────────────────────┼──────────────────┘
                                                     │
                            ▼ Vineflower jar         ▼ ws://127.0.0.1:9876-9885
                            ▼ java-callgraph2     ┌───────────────────────────┐
┌──────────────────────────────────────────────┐  │ DebugBridge Mod           │
│ External Java tools (downloaded into cache)  │  │ (running Minecraft JVM)   │
│  ┌─────────────────┐  ┌────────────────────┐ │  │ github.com/use-ai-for-mc/ │
│  │   Vineflower    │  │  java-callgraph2   │ │  │ debugbridge               │
│  │ Decompiler jar  │  │  + Tiny Remapper   │ │  └───────────────────────────┘
│  │ (single jar)    │  │  (cloned, gradle)  │ │
│  └─────────────────┘  └────────────────────┘ │
└──────────────────────────────────────────────┘
```

## Components

### 1. MCP Server (`src/index.ts`)

Entry point that implements the MCP protocol. Handles:
- Tool registration and discovery
- Request routing to appropriate handlers
- Auto-initialization on first tool call

### 2. Tool Layer (`src/tools/`)

Tools are split into two registries that both feed into `src/tools/index.ts`:

**Static tools** (`src/tools/static/`) — analyse the decompiled sources offline:

| Tool | Purpose | Data Source |
|------|---------|-------------|
| `mc_version` | Set or list the active Minecraft version | Filesystem (cache + index dirs) |
| `mc_search` | Search classes, methods, and fields by name | Symbol Index (JSON) |
| `mc_get_class` | Retrieve full class source | Source Files |
| `mc_get_method` | Retrieve method with context | Source Files |
| `mc_list_classes` | List classes under a package path | Symbol Index |
| `mc_list_packages` | List indexed packages | Symbol Index |
| `mc_find_hierarchy` | Subclasses or interface implementors | Symbol Index |
| `mc_find_refs` | Callers/callees via the callgraph | Callgraph DB (sql.js / SQLite) |

**Runtime tools** (`src/tools/runtime/`) — interact with a running Minecraft client over a WebSocket bridge:

| Group | Tools |
|---|---|
| Connection / execution | `mc_connect`, `mc_execute` |
| World inspection | `mc_snapshot`, `mc_screenshot`, `mc_screen_inspect`, `mc_chat_history` |
| Entity introspection | `mc_nearby_entities`, `mc_entity_details`, `mc_looked_at_entity` |
| Block introspection | `mc_nearby_blocks`, `mc_block_details` |
| Visual markers | `mc_set_entity_glow`, `mc_set_block_glow`, `mc_clear_block_glow` |
| Item textures | `mc_get_item_texture`, `mc_get_item_texture_by_id`, `mc_get_entity_item_texture` |
| Opt-in dev tools (env-gated) | `mc_run_command` (`MCDEV_RUN_COMMAND=1`), `mc_script_logs` (`MCDEV_SCRIPT_LOGS=1`) |

The opt-in tools are skipped from the registry unless the env flag is on; the bridge mod has matching flags so flipping just one side does nothing.

### 3. Decompiler Integration (`src/decompiler/`)

The decompiler stack is **Vineflower** (single self-contained Java jar) plus a Tiny-Remapper-based mapping step. There is no DecompilerMC clone, no Python, and no CFR/Fernflower path.

| File | Responsibility |
|---|---|
| `src/decompiler/index.ts` | `ensureDecompiled()` orchestration + status reporting |
| `src/decompiler/download.ts` | Mojang manifest + jar download (with redirect handling) |
| `src/decompiler/tools.ts` | Vineflower jar download into `<cache-dir>/tools/vineflower.jar` |
| `src/decompiler/vineflower.ts` | `java -jar vineflower.jar` driver |
| `src/decompiler/remapper.ts` | Proguard → Tiny mapping conversion + Tiny-Remapper run |

**Pipeline:**
1. Download the official Minecraft client JAR (versioned, into `<cache-dir>/cache/<version>/jars/`).
2. For 1.x.y versions: download Mojang's ProGuard mappings and remap the jar via Tiny Remapper.
3. For 26.x+ versions: skip the mapping step — the jar is already unobfuscated.
4. Decompile with Vineflower, output into `<cache-dir>/cache/<version>/client/`.

### 4. Symbol Indexer (`src/indexer/index.ts`)

Parses decompiled Java sources and builds a searchable index:
- Extracts class, method, field declarations
- Records line numbers for source lookup
- Stores per-package for efficient loading

**Index Structure (versioned — see [MULTIVER.md](MULTIVER.md)):**
```
<cache-dir>/index/
└── <version>/
    ├── manifest.json              # Per-version metadata
    └── minecraft/
        ├── net.minecraft.client.json
        ├── net.minecraft.world.json
        └── ...                    # one JSON per package
```

### 5. Callgraph System (`src/callgraph/`)

#### Generator (`index.ts`)
- Clones and builds java-callgraph2
- Creates remapped JAR with SpecialSource
- Runs static analysis
- Parses output into SQLite database

#### Query Engine (`query.ts`)
- Optimized SQLite queries with indexes
- Caller/callee lookups in <10ms
- Method search across call graph

**Database Schema:**
```sql
CREATE TABLE calls (
  id INTEGER PRIMARY KEY,
  caller_class TEXT,
  caller_method TEXT,
  caller_desc TEXT,
  callee_class TEXT,
  callee_method TEXT,
  callee_desc TEXT,
  line_number INTEGER
);

CREATE INDEX idx_callee ON calls(callee_class, callee_method);
CREATE INDEX idx_caller ON calls(caller_class, caller_method);
```

### 6. Storage Layer (`src/storage/source-store.ts`)

Provides unified access to:
- Decompiled source files
- Symbol index
- Class/method lookup

## Data Flow

### Initialization Flow

```
init command
    │
    ├─► ensureDecompiled()
    │       ├─► Download Minecraft client jar (download.ts)
    │       ├─► Remap with Tiny Remapper if needed (remapper.ts; 1.x.y only)
    │       ├─► Download Vineflower jar (tools.ts; once per cache)
    │       ├─► Run Vineflower (vineflower.ts)
    │       └─► Return source directory
    │
    ├─► buildIndex()
    │       ├─► Scan all .java files
    │       ├─► Parse declarations
    │       └─► Write per-package JSON under index/<version>/
    │
    └─► ensureCallgraph()  (unless --skip-callgraph)
            └─► see "Callgraph Generation Flow"
```

### Callgraph Generation Flow

```
callgraph command
    │
    ├─► ensureJavaCG()
    │       ├─► Clone java-callgraph2
    │       ├─► Patch build.gradle for Gradle 9.x
    │       └─► Build with ./gradlew gen_run_jar
    │
    ├─► ensureRemappedJar()
    │       ├─► Get client.jar + mappings
    │       └─► Run SpecialSource
    │
    ├─► generateCallgraph()
    │       ├─► Create config files
    │       ├─► Run java-callgraph2
    │       └─► Output method_call.txt
    │
    └─► parseCallgraphAndCreateDb()
            ├─► Parse TAB-delimited output
            ├─► Batch insert into SQLite
            └─► Create indexes
```

### Query Flow

```
mc_find_refs(className, methodName, direction)
    │
    ├─► Check if DB exists
    │
    └─► Query SQLite
            ├─► callers: WHERE callee_class=? AND callee_method=?
            └─► callees: WHERE caller_class=? AND caller_method=?
```

## Key Design Decisions

### 1. Per-Package Index Storage

**Problem:** Single JSON file for 50k+ symbols is slow to load.

**Solution:** Split index by package. Only load packages needed for query.

### 2. SQLite for Callgraph

**Problem:** 400k+ call relationships in memory is expensive.

**Solution:** SQLite with indexes. Queries complete in <10ms.

### 3. Lazy Initialization

**Problem:** Decompilation takes minutes; don't want to block server startup.

**Solution:** Auto-initialize on first tool call. Cache results for subsequent runs.

### 4. Gradle 9.x Compatibility

**Problem:** Java 25 doesn't work with older Gradle versions.

**Solution:** Patch java-callgraph2's build.gradle at runtime to use Gradle 9.3.1 and remove deprecated properties.

### 5. SpecialSource for Callgraph Remapping

**Problem:** java-callgraph2 needs an unobfuscated jar, but for 1.x releases the only jar that survives Vineflower's pipeline is the source tree, not a remapped jar.

**Solution:** When generating the callgraph for a 1.x release, run SpecialSource directly to produce a persistent remapped jar in the cache. The callgraph step then feeds that jar to java-callgraph2.

## Limitations

### Static Analysis Constraints

`mc_find_refs` uses static bytecode analysis which cannot trace:

1. **Reflection** — `Class.forName()`, `Method.invoke()`
2. **JNI Callbacks** — GLFW callbacks, LWJGL native calls
3. **Dynamic Proxies** — Generated at runtime
4. **Lambda Captures** — Method references passed as arguments

### Example

```java
// This WILL be found:
minecraft.mouseHandler.setup();

// This will NOT be found:
GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
    mouseHandler.onMove(win, x, y);  // Called via JNI callback
});
```

## Future Improvements

1. **Server-Side Classes** — Include dedicated server classes
2. **Fabric API Integration** — Index Fabric API alongside vanilla
3. **Incremental Updates** — Only re-index changed classes
4. **AST-based Java parser** — Replace the current regex parser in `src/indexer/parser.ts` (see the project improvement plan)
5. **Pagination on static tools** — Several static tools currently use hard-coded result limits with no `limit` parameter

> Multi-version support shipped in 2026 — see [MULTIVER.md](MULTIVER.md) for the design and current state.
