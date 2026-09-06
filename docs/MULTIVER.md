# Multi-Version Minecraft Support

mcdev-mcp keeps decompiled sources and derived analysis data separate for every
Minecraft version. A version is never inferred from another version's cache.

## Select A Version

Initialize versions with the Java CLI:

```powershell
java -jar mcdev-mcp-3.0.0.jar init -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar init -v 1.20.4
java -jar mcdev-mcp-3.0.0.jar status
```

Within an MCP session, call `mc_version` with `action="set"` before using a
static tool without an explicit version. `action="list"` reports decompiled,
indexed, and callgraph state. Every static tool also accepts an optional
`version` argument, which overrides the active session version for that call.

The server deliberately has no default Minecraft version. If neither an active
version nor an explicit argument exists, the tool asks the user to choose one.

## Versioned Layout

Platform cache roots are selected by `PlatformPaths`:

- Windows: `%LOCALAPPDATA%/mcdev-mcp/Cache`
- macOS: `~/Library/Caches/mcdev-mcp`
- Linux: `${XDG_CACHE_HOME:-~/.cache}/mcdev-mcp`

The relevant per-version shape is:

```text
<cache-root>/
|-- cache/
|   `-- <minecraft-version>/
|       |-- client/                    decompiled Java and resources
|       |-- jars/                      downloaded/remapped inputs
|       `-- indexes/callgraph/         JSONL callgraph publication
`-- index/
    `-- <minecraft-version>/
        `-- symbols.mv.db              H2 symbol index
```

Fabric API source caches use their own versioned directories and are associated
with the selected Minecraft version during indexing.

## Initialization And Rebuilds

`init` downloads, remaps, decompiles, indexes, and generates a callgraph. Use
`--skip-callgraph` only when reference queries are not needed.

```powershell
java -jar mcdev-mcp-3.0.0.jar init -v 1.21.11 --skip-callgraph
java -jar mcdev-mcp-3.0.0.jar callgraph -v 1.21.11
```

`rebuild` reuses prepared source and remapped-JAR caches:

```powershell
java -jar mcdev-mcp-3.0.0.jar rebuild -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar rebuild -v 1.21.11 --with-callgraph
```

All rebuilds publish atomically. A failed candidate leaves the previous valid
H2 database or callgraph generation readable.

## Legacy Index Transition

The 2.2.1 Node line stored per-package JSON indexes. Java does not deserialize
those files into its H2 schema or delete them implicitly. A version with only
legacy index data is reported as `needs rebuild`.

Clean just the derived symbol index, retain the source cache, and rebuild:

```powershell
java -jar mcdev-mcp-3.0.0.jar clean --index -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar rebuild -v 1.21.11 --with-callgraph
```

Use `init` instead of `rebuild` if the prepared source cache is missing.

## Selective Cleanup

```powershell
# One version's callgraph only
java -jar mcdev-mcp-3.0.0.jar clean --callgraph -v 1.21.11

# One version's decompiled/downloaded cache
java -jar mcdev-mcp-3.0.0.jar clean --cache -v 1.21.11

# One version's complete derived state
java -jar mcdev-mcp-3.0.0.jar clean --all -v 1.21.11

# All managed versions and temporary analysis state
java -jar mcdev-mcp-3.0.0.jar clean --all
```

Cleanup rejects paths outside the configured cache root and does not follow
redirected roots or symbolic-link escapes.

## Static Tool Behavior

| Tool | Version behavior |
|---|---|
| `mc_version` | List versions or set the active session version. |
| `mc_search` | Search one version's symbols. |
| `mc_get_class` | Read one version's indexed class/source. |
| `mc_get_method` | Read one version's indexed method/source. |
| `mc_list_classes` | List classes below a package in one version. |
| `mc_list_packages` | List one version's package tree. |
| `mc_find_hierarchy` | Query one version's type relationships. |
| `mc_find_refs` | Query one version's published callgraph. |

Missing or corrupt state produces an actionable Java CLI command. It never
falls through to another version or silently chooses a backend.
