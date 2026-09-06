# mcdev-mcp

`mcdev-mcp` is a standalone Java MCP server for Minecraft mod development. It
downloads and decompiles Minecraft, builds searchable source and callgraph
indexes, and connects coding agents to a running game through the separate
DebugBridge mod.

## Requirements

- Java 26 or newer. Java 26 is the minimum and the release build target.
- No Maven, Gradle, npm, or IDE subscription is required to run a release.

Java 26 corpus qualification and index/callgraph benchmarking are required
release gates. Java 27 and later may be used as separate experiments but are
not release targets.

See [corpus qualification and benchmark inputs](benchmark/README.md) for the
immutable compiler-library manifests and evidence required by those gates.

## Install

Download one of these assets from the matching GitHub Release:

- `mcdev-mcp-<version>.jar` and its `.sha256` file for direct Java use.
- `mcdev-mcp-<version>.mcpb` for clients that install MCPB bundles.

The JAR is the complete application. It contains the MCP server, CLI, Javac
indexer, Class-File callgraph scanner, H2 storage, Vineflower, and Tiny
Remapper. The MCPB contains that exact release JAR plus a small packaging-only
launcher.

The npm package `mcdev-mcp` 2.2.1 is the final Node release. Java releases are
installed from GitHub Releases or as MCPB bundles. Deprecating the old npm
package is a separate, explicitly approved release action and is not performed
by this repository's release workflow.

## Configure An MCP Client

Use an absolute path to the downloaded JAR:

```json
{
  "mcpServers": {
    "mcdev-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "C:/tools/mcdev-mcp-3.0.0.jar",
        "serve"
      ]
    }
  }
}
```

Initialize each Minecraft version once before using its static-analysis tools:

```powershell
java -jar mcdev-mcp-3.0.0.jar init -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar status
```

Useful maintenance commands:

```powershell
java -jar mcdev-mcp-3.0.0.jar rebuild -v 1.21.11 --with-callgraph
java -jar mcdev-mcp-3.0.0.jar callgraph -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar clean --index -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar clean --all
```

Run `java -jar mcdev-mcp-3.0.0.jar --help` for the complete CLI.

## Storage Migration

The Java source index is an H2 database and is rebuilt atomically. Callgraph
data is a generation-addressed JSONL bundle. Both are local derived data, not
portable user documents.

Indexes created by the final Node release used a legacy JSON layout. The Java
server reports those versions as needing a rebuild and does not silently
reinterpret them. Run `clean --index -v <version>` followed by `init` or
`rebuild`; the cached sources can be retained. A clean Java rebuild replaces
the legacy index with H2 data.

## Environment

Supported runtime settings:

| Setting                 | Purpose                                                                  |
|-------------------------|--------------------------------------------------------------------------|
| `DEBUGBRIDGE_PORT`      | Use a specific DebugBridge port instead of the normal scan.              |
| `MCDEV_RUN_COMMAND`     | Enable the opt-in `mc_run_command` tool when set to `1` or `true`.       |
| `MCDEV_SCRIPT_LOGS`     | Preserve the 2.2.1 opt-in script-log behavior when set to `1` or `true`. |
| `MCDEV_SESSION_LOG_DIR` | Enable script logs and choose their directory explicitly.                |
| `MCDEV_MCP_DEBUG_LOG`   | Write protocol-safe diagnostics to `on`, `off`, or a chosen path.        |
| `MCDEV_INDEX_THREADS`   | Bound Javac indexing parallelism to a positive integer.                  |

`MCDEV_MCP_SKIP_SMOKE=1` is only a local MCPB build-script escape hatch. It is
not read by the server.

Backend selectors, parser selectors, worker commands and arguments, worker
count/batch/heap/retry/path/marker/fallback controls, the remapper heap switch,
and argument-capture hooks from the retired implementation are unsupported.
The Java server has one analysis pipeline and no worker subprocess protocol.

## DebugBridge

DebugBridge remains a separate Fabric mod inside Minecraft's JVM. mcdev-mcp
speaks its localhost WebSocket protocol and does not add the mod as a runtime
dependency. The fixture-backed compatibility baseline is DebugBridge 2.0.0;
newer additive capabilities are detected from the status response. Install a
DebugBridge build compatible with your Minecraft version to use runtime tools.

## Build From Source

The checked-in Gradle wrapper requires a Java 26 development kit:

```powershell
.\gradlew.bat clean check shadowJar --console=plain
```

See [Architecture](docs/ARCHITECTURE.md), [multi-version storage](docs/MULTIVER.md),
and [Vineflower integration](docs/VF.md) for implementation details.
