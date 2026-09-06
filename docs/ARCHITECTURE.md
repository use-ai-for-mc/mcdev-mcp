# Architecture

## Runtime Shape

mcdev-mcp ships as one Java 26 shaded executable JAR. The same artifact runs
the human-facing CLI and the STDIO MCP server:

```text
MCP client
  -> java -jar mcdev-mcp-<version>.jar serve
     -> official MCP Java SDK
     -> typed tool and resource catalogs
     -> static-analysis services
     -> DebugBridge WebSocket client
```

The server has no analysis worker processes and no internal serialization
protocol. Filesystem and H2 work runs on virtual threads with explicit
cancellation. DebugBridge remains an independent mod and therefore remains a
real JSON/WebSocket boundary.

## Package Boundaries

Production code lives below `dev.mcdevmcp`:

| Package              | Responsibility                                                 |
|----------------------|----------------------------------------------------------------|
| `app`                | CLI commands, startup, and analysis orchestration.             |
| `mcp`                | SDK adapter, STDIO server, tool catalog, and resource catalog. |
| `tools.statictool`   | Source, hierarchy, version, and reference tools.               |
| `tools.runtime`      | DebugBridge-backed live-game tools.                            |
| `analysis.index`     | Javac-based source indexing and typed index models.            |
| `analysis.callgraph` | JDK Class-File API scanning and graph publication.             |
| `analysis.decompile` | Minecraft download, Tiny Remapper, and Vineflower.             |
| `storage`            | Platform paths, H2 repositories, JSONL bundles, and cleanup.   |
| `bridge`             | Nonblocking DebugBridge envelopes, validation, and sessions.   |
| `packaging`          | Deterministic MCPB metadata and packed-artifact smoke tests.   |
| `support`            | Environment, JSON, logging, cancellation, and version helpers. |

The Gradle build has four projects with one-way dependencies:

```text
benchmark ----\
               -> root application -> mcp-tool-api
conformance --/
```

`mcp-tool-api` is the only production library boundary. `benchmark` and
`conformance` are independently buildable harness projects that consume the
root application; the root never depends on them. They are not server
artifacts, and their JSON/reporting and Tomcat dependencies cannot enter the
production runtime. The root project still produces the only release JAR.

`mcp-tool-api` is an explicit JPMS module named
`dev.mcdevmcp.mcp.tool.api`. Its public descriptor exports whole-value JSON and
argument decoders, explicit Java JSON type tokens, protocol content values,
ordinary results, and generic `StructuredToolResult<T>` values. A `TypedJson<T>`
keeps raw JSON beside the `Class<T>` or `TypeRef<T>` it is meant to become.
Structured payloads remain Java records or objects until `McpSdkAdapter` places
only their value in MCP `structuredContent`; Java class names never enter wire
JSON. Execution, cancellation, catalogs, transport, and Minecraft policy stay
in the root application. The module requires the official MCP core API
transitively. The reviewed MCP SDK
snapshot publishes invalid automatic module names, so this subproject uses a
build-scoped Gradle artifact transform to supply complete descriptors for
`mcp-core` and the test-only Jackson 3 provider. A named-module smoke test
verifies mapper and schema-validator service loading without
`ALL-MODULE-PATH`, `--add-reads`, or `--add-exports`.
Because transformed dependencies are not republished, external publication of
the tool API module remains deferred until the SDK fixes its own metadata or the
same transform is supplied as a consumer build convention.

The root executable deliberately remains a classpath application. Its shaded
JAR is a deployment format for direct `java -jar` and MCPB use, not the unit of
source architecture; module descriptors from library inputs are excluded when
the fat JAR is assembled. Future optional runtime backends may ship in a
separate modular distribution without changing the single-JAR baseline.

## Analysis Pipeline

`init -v <version>` performs one owned pipeline:

1. Resolve the Mojang version metadata and download the client JAR.
2. Convert official mappings and remap with embedded Tiny Remapper.
3. Decompile the remapped JAR with embedded Vineflower.
4. Parse Java source with Javac and atomically publish an H2 symbol database.
5. Scan JVM class files with the Java Class-File API and publish a deterministic
   JSONL callgraph bundle.

Javac receives batches of source files but produces one typed logical index.
`MCDEV_INDEX_THREADS` bounds parallel indexing; it does not select an alternate
backend. Callgraph generation reads class-file instructions because invocation
edges are bytecode facts, while source declarations and locations remain the
Javac indexer's responsibility.

## Storage And Rebuilds

Each Minecraft version owns its cache and index state. The symbol database is
`symbols.mv.db`. Callgraph publication uses immutable generation directories,
checksummed JSONL data/index files, and an atomic current-generation pointer.
Writers validate candidates before publication; readers never observe a
partially replaced index.

The final Node release's package JSON indexes are legacy input, not a Java
storage format. Their presence makes status report `needs rebuild`. Users run
`clean --index -v <version>` and then `init` or `rebuild`; no SQL server or
external database service is required because H2 is embedded in the JAR.

## JSON Boundaries

The MCP SDK's `McpJsonMapper` is the single JSON abstraction. MCP messages,
DebugBridge envelopes, metadata, manifests, and JSONL records deserialize into
typed Java records or bounded generic JSON values at protocol edges. Production
code does not introduce a second JSON engine.

## Packaging And Release

The root `manifest.json` is deterministic Java-generated catalog metadata. It
contains no server command or Node runtime selector.

MCPB is the sole packaging exception. `packaging/mcpb/` owns a minimal
`bootstrap.cjs`, package metadata, and its packaging dependency. The launcher
requires Java 26 and starts the bundled JAR without preview features. All npm commands
in `scripts/build-mcpb.ps1` run with that directory as their working directory;
nothing there is part of direct JAR execution.

The release workflow builds the JAR once on Java 26, records its hash, and
runs that exact artifact on Java 26. The MCPB is packed around the same bytes. A
read-only verification job admits exactly three publishable assets: JAR,
checksum, and MCPB. Only the final publishing job receives release write
permission.

## Compatibility Evidence

The frozen 2.2.1 Node release is a read-only differential oracle, identified by
`contracts/node-oracle.json`. Tests clone it into ignored `.superpowers`
scratch, build it there, and verify that the source checkout remains unchanged.
It is never restored into the Java branch.

DebugBridge protocol fixtures are captured from the 2.0.0 baseline. Envelope
and endpoint compatibility is tested locally; live Minecraft behavior remains
an acceptance test against a user-launched compatible mod.
