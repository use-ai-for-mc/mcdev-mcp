# mcdev-mcp

`mcdev-mcp` is a standalone Java MCP server for Minecraft mod development. It
downloads and decompiles Minecraft, builds searchable source and callgraph
indexes, and connects coding agents to a running game through the separate
DebugBridge mod.

## Requirements

- The MCPB bundle and the self-contained application image need nothing
  installed: they carry their own Java runtime and a native launcher.
- The plain JAR needs a Java 28 JDK, launched with `--enable-preview`. Java 28
  is an early-access build until its general availability in March 2027.
- No Maven, Gradle, npm, or IDE subscription is required to run a release.

Preview bytecode is specific to Java 28; other JDK feature versions
cannot run this release. The application image, which the MCPB bundle is
built around, enables preview itself; direct JAR launches must include the
flag.

## Install

Download one of these assets from the matching GitHub Release:

- `mcdev-mcp-<version>-<platform>.mcpb` for clients that install MCPB bundles.
- `mcdev-mcp-<version>-<platform>.zip`, the self-contained application image,
  for any other MCP client.
- `mcdev-mcp-<version>.jar` and its `.sha256` file for direct Java use.

`<platform>` is `windows-x64`, `linux-x64` or `macos-aarch64`. An MCPB manifest
can name an operating system but not a processor, so a client accepts a bundle
on any machine running that system; it only starts on the processor in its
name.

The JAR is the complete application. It contains the MCP server, CLI, Javac
indexer, Class-File callgraph scanner, file-backed index storage, Vineflower, and Tiny
Remapper. The application image is that exact release JAR with a Java runtime
and a native launcher, and the MCPB is that image with a manifest that starts
the launcher.

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
        "--enable-preview",
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
java --enable-preview -jar mcdev-mcp-3.0.0.jar init -v 1.21.11
java --enable-preview -jar mcdev-mcp-3.0.0.jar status
```

Useful maintenance commands:

```powershell
java --enable-preview -jar mcdev-mcp-3.0.0.jar rebuild -v 1.21.11 --with-callgraph
java --enable-preview -jar mcdev-mcp-3.0.0.jar callgraph -v 1.21.11
java --enable-preview -jar mcdev-mcp-3.0.0.jar clean --index -v 1.21.11
java --enable-preview -jar mcdev-mcp-3.0.0.jar clean --all
```

Run `java --enable-preview -jar mcdev-mcp-3.0.0.jar --help` for the complete CLI.

## Storage Migration

The Java source index is one JSONL file that is rebuilt atomically and held in
memory while the server answers queries. It holds every top-level type and
every member type declared inside one; a member type answers to its binary
name (`Outer$Inner`) and to the dotted name it has in source (`Outer.Inner`).
Local and anonymous classes are not indexed. Callgraph data is a
generation-addressed JSONL bundle. Both are local derived data, not portable
user documents.

Indexes created by the final Node release used a legacy JSON layout. The Java
server reports those versions as needing a rebuild and does not silently
reinterpret them. Run `clean --index -v <version>` followed by `init` or
`rebuild`; the cached sources can be retained. A clean Java rebuild replaces
the legacy index. The `symbols.mv.db` database written by earlier 3.0.0
development builds is reported the same way, and `rebuild` deletes it once the
new index is in place. So is an index in an older JSONL format, such as one
built before member types were indexed: `status` marks it as needing a rebuild
and `rebuild -v <version>` replaces it from the cached sources.

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

The checked-in Gradle wrapper requires a Java 28 development kit:

```powershell
.\gradlew.bat clean check shadowJar --console=plain
```

### Self-contained application image and MCPB bundle

```powershell
.\gradlew.bat appImageZip appImageSmoke mcpbBundle mcpbBundleSmoke --console=plain
```

This links a trimmed Java runtime, wraps it and the release JAR in a jpackage
application image, and writes `build/distributions/mcdev-mcp-<version>-<platform>.zip`
and, packed with the pinned official packer, which needs Node.js and npm,
`build/distributions/mcdev-mcp-<version>-<platform>.mcpb`. Both are built for
the platform they are built on. Unpack the image anywhere writable and point an
MCP client at the launcher; no `java` command or flag is involved:

```json
{
  "mcpServers": {
    "mcdev-mcp": {
      "command": "C:/tools/mcdev-mcp/mcdev-mcp.exe",
      "args": ["serve"]
    }
  }
}
```

The launcher is `mcdev-mcp/bin/mcdev-mcp` on Linux and
`mcdev-mcp.app/Contents/MacOS/mcdev-mcp` on macOS. The first `serve` session
records a startup cache next to the JAR in the background; later sessions start
from it and answer about three times sooner. CI and the release workflow build
and exercise the image and the bundle on Linux, Windows and macOS.

See [Architecture](docs/ARCHITECTURE.md), [multi-version storage](docs/MULTIVER.md),
and [Vineflower integration](docs/VF.md) for implementation details.
