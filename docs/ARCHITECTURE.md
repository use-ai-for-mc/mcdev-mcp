# Architecture

## Runtime Shape

mcdev-mcp ships as one Java 28 shaded executable JAR. The same artifact runs
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
protocol. Filesystem and index work runs on virtual threads with explicit
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
| `storage`            | Platform paths, the symbol index, JSONL bundles, and cleanup.  |
| `bridge`             | Nonblocking DebugBridge envelopes, validation, and sessions.   |
| `packaging`          | Deterministic MCPB metadata for each packaged platform.        |
| `support`            | Environment, JSON, logging, cancellation, and version helpers. |

The Gradle build has three projects with one-way dependencies:

```text
root application -> storage-model -> mcp-tool-api
                \-> mcp-tool-api
```

`mcp-tool-api` and `storage-model` are the production library boundaries.
The root project produces the only release JAR.

Shared build conventions live in the `build-logic` included build: the Java
preview toolchain, the generated module descriptors for the MCP SDK, the
cutover scan, and the custom verification task types. Dependency versions are
declared once in `gradle/libs.versions.toml`.

`mcp-tool-api` is an explicit JPMS module named
`dev.mcdevmcp.mcp.tool.api`. Its public descriptor exports generated JSON object
schemas, `ToolInput<A>` decode tokens, the logical JSON type registry, generic
`ToolBinding`/`ToolHandlers` execution, SDK `McpSchema.Content` results, and
`StructuredToolResult<T>` values. Java type metadata stays server-side; JSON
Schema describes semantic shape only. Catalogs, availability, transport, and
Minecraft-domain values stay in the root application. `tools.json` retains names
and descriptions; generated `ToolInput` schemas are the sole advertised input
schema. Structured payloads remain Java records or objects until
`McpSdkAdapter` places only their value in MCP `structuredContent`. The module
requires the official MCP core API transitively. The reviewed MCP SDK
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
4. Parse Java source with Javac and atomically publish the symbol index file.
5. Scan JVM class files with the Java Class-File API and publish a deterministic
   JSONL callgraph bundle.

Javac receives batches of source files but produces one typed logical index.
`MCDEV_INDEX_THREADS` bounds parallel indexing; it does not select an alternate
backend. Callgraph generation reads class-file instructions because invocation
edges are bytecode facts, while source declarations and locations remain the
Javac indexer's responsibility.

## Storage And Rebuilds

Each Minecraft version owns its cache and index state. The symbol index is
`symbols.jsonl`: a header line followed by every package, type, field, method
and parameter row. A query loads the file once into an immutable in-memory
index, which is cached per process and reloaded when the file is replaced.
Callgraph publication uses immutable generation directories,
checksummed JSONL data/index files, and an atomic current-generation pointer.
Writers validate candidates before publication; readers never observe a
partially replaced index.

The final Node release's package JSON indexes are legacy input, not a Java
storage format. Their presence makes status report `needs rebuild`. Users run
`clean --index -v <version>` and then `init` or `rebuild`; no database engine
or external service is involved.

## JSON Boundaries

The MCP SDK's `McpJsonMapper` is the single JSON abstraction. MCP messages,
DebugBridge envelopes, metadata, manifests, and JSONL records deserialize into
typed Java records or bounded generic JSON values at protocol edges. Production
code does not introduce a second JSON engine.

## Packaging And Release

The root `manifest.json` is deterministic Java-generated catalog metadata. It
contains no server command or Node runtime selector.

The `appImage` task wraps the release JAR in a jpackage application image with a
runtime linked from the JDK modules the JAR uses, so an installation needs no
JDK and no command-line flags. Three details exist only because the server runs
with preview features over standard streams:

- jlink cannot generate the class-data archive of a preview launch, so the build
  dumps one into the runtime.
- The launcher sends JVM logging to standard error; the JVM's default is
  standard output, which carries the MCP stream.
- The launcher always names an ahead-of-time cache beside the JAR. A JVM cannot
  use and record a cache in one run, and accepts one only for the exact JAR file
  it was recorded with, so `StartupCache` starts a short `StartupCacheTrainer`
  process when the cache is missing or its recorded identity no longer matches
  the JAR. The trainer serves one scripted session in-process, ends the
  recording and publishes the cache atomically.

The task builds the image aside and renames it into place. Windows refuses to
rename a directory a running server holds files in, so that build fails and the
old image stays whole; deleting it first would remove only the unlocked files
and leave the server unable to start again.

The `mcpbBundle` task packs that image as an MCPB bundle. The bundle's manifest
is the one generated for the operating system it is built on: a `binary` server
whose command is the image's launcher, declared compatible with that system
alone. No script sits between the client and the server, so the server itself
treats a setting the client left as an unresolved `${user_config.*}` placeholder
as absent. The packer is the only packaging exception to the absence of Node:
`packaging/mcpb/` pins it, the task installs it there, and it runs only to pack
and unpack. The task requires every file of the image to come back from the
unpacked bundle unchanged, because the packer leaves out files it takes for
development leftovers.

The release workflow builds the JAR and the manifests once on Java 28, records
the JAR's hash, and runs that exact artifact on Java 28. A job per platform
wraps the same bytes in an application image and an MCPB bundle and starts both
as a client would. A read-only verification job admits exactly eight
publishable assets: the JAR, its checksum, and the image and bundle of each of
the three platforms listed in `scripts/release-platforms.psd1`. Only the final
publishing job receives release write permission.

## Compatibility Evidence

The 2.2.1 Node release, at the commit recorded in `contracts/node-oracle.json`,
is the reference the Java server was ported from. No test runs it: the CLI and
the MCP surface are held in place by the recorded fixtures under
`src/test/resources/contracts`.

DebugBridge protocol fixtures are captured from the 2.0.0 baseline. Envelope
and endpoint compatibility is tested locally; live Minecraft behavior remains
an acceptance test against a user-launched compatible mod.
