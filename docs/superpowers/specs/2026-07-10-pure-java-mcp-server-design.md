# Pure Java MCP Server Design

## Status

Approved on 2026-07-10 after an independent design review. This specification
replaces the earlier Java worker and Bun/TypeScript migration designs. The
implementation plan derived from it is the only active Superpowers plan for
the rewrite.

## Context

mcdev-mcp currently combines a TypeScript MCP server with Java subprocesses for
Java-specific analysis. That architecture creates duplicate TypeScript and Java
models, worker lifecycle code, line-delimited JSON protocols, separate build
toolchains, and fallback behavior that can silently reduce indexing accuracy.

The server already requires a JDK for Minecraft decompilation and analysis.
DebugBridge is also a Java Fabric mod whose shared `core` module owns its
localhost WebSocket protocol. A standalone Java server therefore removes more
complexity than a Bun migration or a collection of Java workers.

The final server implementation and primary runtime artifact are one shaded,
executable Java JAR. The MCPB is a second distribution format containing that
exact JAR plus a packaging-required Node launch shim; it is not a second server
implementation. DebugBridge remains a separate mod running in Minecraft's JVM,
so MCP JSON-RPC and the DebugBridge WebSocket remain real serialization
boundaries. Internal indexer and callgraph worker protocols disappear.

## Goals

- Rewrite the MCP server, CLI, analysis pipeline, storage, and DebugBridge
  client in pure Java.
- Distribute one cross-platform shaded JAR as the primary artifact.
- Use the official MCP Java SDK 2.0.0 asynchronous server over STDIO without
  Spring or another web framework.
- Use JDK compiler tree APIs as the sole production source indexer.
- Remove regex-based Java source parsing, `java-parser`, the TypeScript AST
  parser, and every Java-to-regex fallback.
- Use the Java Class-File API to generate the complete callgraph needed by
  `mc_find_refs` without java-callgraph2.
- Preserve every public MCP tool, resource, CLI command, environment switch,
  and result/error contract unless this specification explicitly replaces it.
- Keep Java 25 as the minimum runtime and compilation target.
- Evaluate Java 26 and later as preferred performance runtimes, documenting
  that preference only after measured indexing and callgraph comparisons meet
  the policy below.
- Preserve MCPB through a minimal packaging-only Node launcher if its manifest
  still cannot launch Java directly.
- Keep DebugBridge independent while testing its real JSON wire contract from
  versioned fixtures.
- Prove a small SDK-backed typed argument-binding extension inside an
  independently buildable Gradle library before considering separate
  publication or an upstream proposal.

## Non-Goals

- Do not merge mcdev-mcp into the DebugBridge repository or Minecraft process.
- Do not use Kotlin, Spring Boot, Ktor, GraalVM native-image, or preview JDK
  features.
- Do not add a production SSE or Streamable HTTP transport as parity work. The
  frozen TypeScript server exposes only STDIO; Task 13's URL-based HTTP server
  is a test-only MCP conformance harness over the same registry.
- Do not preserve the TypeScript server as a permanent compatibility layer.
- Do not retain Bun, a TypeScript 6 compatibility package, or a Rust-Bun canary.
- Do not reproduce java-callgraph2 reports unrelated to `mc_find_refs`.
- Do not infer reflective, service-loader, dependency-injection, or data-flow
  calls that are absent from bytecode invocation instructions.
- Do not publish a DebugBridge protocol artifact before the fixture-backed wire
  boundary has remained stable across two independent releases.
- Do not publish the typed tool API library or claim an upstream MCP
  SDK namespace before multiple static and runtime tool families prove its API
  and error model.

## Architecture

The repository becomes one Gradle multi-project build with one root application
and one internal Java library rather than multiple runtime workers. The root
still produces the only server and release JAR:

```text
:mcp-tool-api       SDK/JDK-only typed argument decoding

dev.mcdevmcp
|-- app              executable entry point, CLI, lifecycle
|-- mcp
|   |-- transport    MCP SDK/STDIO adaptation
|   |-- tool         application tool registry and execution policy
|   `-- resource     MCP resource registry
|-- analysis
|   |-- decompile    downloads, mappings, Tiny Remapper, Vineflower
|   |-- index        Javac parser and symbol database writer
|   `-- callgraph    Class-File scanner and call database writer
|-- storage          paths, version state, symbol/call queries
|-- bridge           DebugBridge WebSocket session and wire adapters
|-- tools
|   |-- statictool   static-analysis MCP tools
|   `-- runtime      DebugBridge MCP tools
`-- support          configuration, logging, JSON, validation
```

There is one production entry point:

```text
java -jar mcdev-mcp-<version>.jar <command>
```

The JAR contains the server, Java indexer, callgraph generator, H2 MVStore JDBC
driver, Tiny Remapper, Vineflower, resources, and all normal runtime
dependencies. It does not download or launch an indexer, callgraph tool,
Tiny Remapper JAR, or Vineflower JAR.

The build uses:

- Gradle Wrapper 9.6.1, including executable POSIX `gradlew` metadata.
- Java toolchain 25 and `options.release = 25`.
- Gradle Shadow 9.5.1 for the executable JAR.
- MCP Java SDK 2.0.0 (`io.modelcontextprotocol.sdk:mcp`).
- Picocli 4.7.7 for the command surface.
- H2 2.4.240 for pure-Java symbol and callgraph MVStore storage. H2 supports a single closed `.mv.db` file, durable validation, and promotion without native access. sqlite4j/Endive remains unsuitable because its VFS is memory-backed, needs explicit host backup, uses a non-threadsafe SQLite build, and has no WAL.
- Vineflower 1.11.2 and Tiny Remapper 0.10.4 as embedded libraries.
- JUnit 6.1.0 for tests.

Dependency versions remain pinned through a Gradle version catalog. The final
JAR is built without JPMS module boundaries so service descriptors from shaded
libraries can be merged predictably.

## Java Engineering Conventions

- Use final Java 25 language features where they simplify the implementation.
  The executable entry point uses the Java 25 launch protocol through an
  instance `void main(String[] arguments)` method rather than the legacy
  `public static void main(String[] arguments)` incantation.
- Production classes, interfaces, records, and enums are top-level types by
  default. Nesting is reserved for a tiny private implementation detail that
  is inseparable from its owner or for an SDK contract that requires it; a
  reusable domain value or cross-task interface gets its own focused file.
- Represent domain values with designated Java or JDK types at their
  boundaries. In particular, use `URI`, `Path`, `Duration`, `Instant`, enums,
  or validated value records instead of carrying URIs, filesystem paths,
  timeouts, timestamps, modes, or similar closed concepts as unvalidated
  `String` values. Protocol text, identifiers, MIME types, descriptors, and
  other genuinely open vocabularies remain strings.
- Use the SDK 2.0 `McpJsonMapper` supplied by `McpJsonDefaults` as the only
  production and test JSON implementation. Do not add Gson, a second JSON
  engine, direct Jackson imports or annotations, or `JsonNode`. Fixed wire
  shapes use top-level records; genuinely open JSON uses deeply immutable
  `Map<String,Object>` and `List<Object>` values.
- Never serialize `Path` implicitly. Convert it explicitly to the exact URI or
  string form defined by the existing wire contract, and parse it back at the
  boundary. The SDK mapper remains appropriate for `URI`, `Duration`,
  `Instant`, enums, records, and generic JSON collections covered by contract
  tests.
- The current IntelliJ-formatted Java source and its surrounding style are
  authoritative. Preserve that formatting in later edits, follow the current
  code style for new files, and do not introduce broad reformatting or a
  competing formatter as part of feature work.
- Compile every Java source set with `-Xlint:all -Werror`. Each task gate also
  runs IntelliJ MCP project compilation and changed-file problem inspection;
  fix actionable errors and warnings before review. Any unavoidable
  suppression must be narrow and explain why the warning is intentionally
  deferred.

## Migration Strategy

The Java server is built alongside the TypeScript server until differential
tests prove parity. Each migration slice replaces one behaviorally coherent
area. The TypeScript implementation remains a test oracle, not a new shared
runtime abstraction.

Execution is subagent-driven in the isolated `codex/java25-indexer-callgraph`
worktree. Each migration slice has one independently scoped implementer, an
explicit task brief and report, a commit-range review package, and an
independent reviewer checking both specification compliance and implementation
quality. The controller/integrator owns cross-slice interfaces and the durable
progress ledger. No later slice begins while a Critical or Important review
finding remains open. Implementer and reviewer model/reasoning effort are
chosen per task: the least expensive model that can reliably handle the task,
with stronger reasoning reserved for parser semantics, concurrency, protocol
parity, release integration, and the final whole-branch review.

The isolated Java worktree cuts over early: `src/**/*.ts`, retired tests, Node
production metadata, the Java worker protocol, and obsolete build scripts are
deleted before parity is complete. `cutoverCheck` guards the tracked repository
surface. The clean original `master` checkout remains the immutable Node oracle
for later differential tests, which must materialize or invoke it from ignored
scratch rather than restore legacy source here. Git history remains the
migration record.

## MCP Surface

The pinned baseline TypeScript server's actual `tools/list` response is the
authoritative migration inventory. This intentionally resolves the current
drift where the runtime registry exposes `mc_record_video` but `manifest.json`
omits it: `mc_record_video` remains public. A checked-in canonical response
fixture is captured before migration. The Java tool registry must match that
fixture exactly, `manifest.json` is generated from the Java registry, and CI
asserts exact name/schema parity so the two inventories cannot drift again.

The Java server preserves:

- MCP initialization metadata, instructions, STDIO transport, and capability
  negotiation.
- `tools/list`, `tools/call`, `resources/list`, and `resources/read` behavior.
- All current `mc_*` tool names, descriptions, JSON Schemas, default limits,
  maximum limits, truncation indicators, and text result formatting.
- Resource URIs `mcdev://guides/python-scripting` and
  `mcdev://guides/dev-loop`.
- Unknown-tool and handler-failure responses using MCP tool errors rather than
  terminating the STDIO process.

The server uses the MCP SDK's asynchronous API over STDIO. Internal tool
handlers expose JDK `CompletionStage` values and a cancellation signal; Reactor
types remain confined to the MCP SDK adapter. DebugBridge/WebSocket operations
therefore remain nonblocking end to end. Filesystem, H2, and other blocking
handlers run on a Java 25 virtual-thread executor rather than a Reactor event
loop. Cancelling an MCP request cancels its future and signals the underlying
operation. This preserves concurrent in-flight calls, including while a long
video capture or wait tool is pending, without spreading reactive types through
the application.

One process-scoped raw `McpJsonMapper` deserializes tool metadata and backs
application JSON boundaries. SDK 2.0 exposes tool arguments only as
`Map<String,Object>`, but its mapper provides `convertValue`; the application
therefore adds only a thin generic `ToolBinding<A>`/`ArgumentDecoder<A>` adapter
that converts the complete argument map into a top-level per-tool record before
calling `ToolHandler<A>`. It does not add a second generic JSON tree or a broad
typed-getter facade. When a wire representation differs from the domain type,
the decoder explicitly maps a package-private wire record into a domain record,
for example milliseconds to `Duration`, path text to `Path`, or a wire name to
an enum. Raw maps remain only for genuinely open payloads. Tool schemas remain
deeply immutable ordered maps. `NodeParityJsonMapper` wraps the raw mapper only
at the MCP transport boundary to adapt SDK-specific typed responses required by
the frozen Node contract; DebugBridge and other application code always receive
the unwrapped mapper.

The SDK/JDK-only typed tool contracts live in the internal `mcp-tool-api`
`java-library` subproject under `dev.mcdevmcp.mcp.tool.api`. They carry Java
type tokens beside arbitrary JSON, decode complete argument maps, and represent
ordinary or typed structured tool results. The root application retains
`ToolBinding`, `Cancellation`, executor selection, catalogs, transport
adaptation, and Minecraft behavior. The child cannot depend on or
import those root-project types, while the application has the only project
dependency direction. Production declares only the official SDK `mcp-core`
module as an API dependency because `McpJsonMapper` appears in the public
signature; the official Jackson 3 mapper module is test-only in the child.

This enforced Gradle boundary is the rehearsal for possible extraction to a
separate repository. The library is the explicit JPMS module
`dev.mcdevmcp.mcp.tool.api`. The reviewed MCP SDK snapshot and current
upstream source derive invalid automatic names containing hyphens for
`mcp-core` and `mcp-json-jackson3`, tracked by
[MCP Java SDK issue #560](https://github.com/modelcontextprotocol/java-sdk/issues/560).
The library build supplies complete replacement descriptors through a scoped
artifact transform and verifies service discovery in a named-module smoke. The
shaded application remains classpath-based throughout.

The library is not split into another repository, published, or proposed
upstream until real tool usage stabilizes the abstraction and its error model.
This avoids committing to a compatibility surface prematurely.

Within the root application, Java's lack of friend packages requires explicit
MCP capsule operations. Resource creation with the process mapper,
executor-aware tool-catalog creation, and complete STDIO/SDK server creation
are the only public construction operations across the resource, tool, and
transport packages. The transport operation hides all lower-level adapter and
lifecycle constructors. These are deliberate stage facades rather than public
implementation helpers.

STDOUT is reserved exclusively for MCP JSON-RPC while `serve` is running.
Diagnostics use STDERR or the existing opt-in debug log file. No logging
backend may write banners or progress output to STDOUT during MCP operation.

The production STDIO entry point is tested end to end with a Java MCP client and
raw-process failure cases. Because `@modelcontextprotocol/conformance@0.1.16`
only drives URL-based server transports, the official suite runs against a
test-only Streamable HTTP harness that uses the exact same server metadata,
tool registry, resource registry, handlers, and JSON mapper as production.
Passing that harness is an additional acceptance gate; it does not replace the
project's STDIO integration tests or add an HTTP transport to the release JAR.
The harness prefers an SDK-supported container-free JDK provider if one exists;
otherwise it uses the SDK's built-in Servlet provider with an upstream-tested
embedded container. Servlet/container dependencies are declared only in the
conformance source set when it exists and are absent from production runtime
and shaded artifacts. Dependency versions resolve reproducibly from exact
reviewed coordinates while daily Dependabot updates keep them current; dynamic
selectors and unused catalog aliases are not permitted.

## CLI Surface

Picocli reproduces these commands and options:

```text
serve
init -v <version> [--skip-callgraph]
callgraph -v <version>
rebuild -v <version> [--with-callgraph]
status [-v <version>]
clean [--callgraph|--cache|--index|--all] [-v <version>]
```

Command exit codes, progress line shape (`[stage] N% - message`), supported
Minecraft version validation, and readable filesystem/process diagnostics are
part of parity. Initialization remains a CLI operation; MCP tools remain query
and runtime-interaction surfaces.

## Source Indexing

The production indexer uses only supported JDK APIs:

- `javax.tools.JavaCompiler`
- `javax.tools.StandardJavaFileManager`
- `com.sun.source.util.JavacTask`
- `com.sun.source.util.SourcePositions`
- `com.sun.source.tree.*`
- Java 25's finalized `java.lang.classfile` API for the remapped-JAR catalog

`ToolProvider.getSystemJavaCompiler()` must be present. A JRE-only environment
or Java below 25 fails before downloads or index mutation begin.

The request carries a typed `MinecraftVersion` and immutable typed
`SourceRoot` values. Each root owns its `SourceNamespace`, optional typed
`FabricApiVersion`, and normalized `Path`; the request has no raw Fabric
version field. The indexer discovers sources without following links and
strictly decodes the complete corpus before compiler or database work.

The indexer parses sorted source paths in bounded batches. Explicit sources
and every on-demand `SOURCE_PATH` dependency are in-memory overlays from the
strictly decoded corpus; Javac never rereads source text from disk. The exact
remapped JAR followed by the typed request classpath forms `CLASS_PATH`. Each
parser task owns and closes its compiler and file manager; compiler objects
never escape or cross threads. CPU concurrency is bounded by available
processors and `MCDEV_INDEX_THREADS`. Results are merged by typed source
identity, portable relative path, and declaration offset.

The immutable corpus owns exactly one strictly decoded `String` per source
file. Per-worker file objects and binary-name aliases reference that shared
text rather than copying it. The executor admits at most the configured worker
count, so queued and completed batch results are also bounded by that count.
Increasing the worker count may multiply active Javac state, but it must not
multiply the decoded corpus or create an unbounded task/result queue.

Before parsing sources, the Class-File API builds a type catalog from the same
remapped JAR. For every class present in that JAR, the catalog is authoritative
for binary identity, superclass, and directly implemented interfaces. Javac AST
declarations join to the catalog by fully qualified binary name; the indexer
never guesses a hierarchy target from a simple name.

For source-only types that have no class-file entry, Javac attribution runs
against the supplied source path and classpath. `Trees.getElement` and
`TypeMirror` identity resolve imported, wildcard-imported, same-package, and
nested hierarchy references. If an indexed declaration's binary name,
superclass, interface, member signature, or other stored semantic identity is
unresolved or ambiguous, the build fails instead of storing a guessed name.
Syntax errors always fail. Unrelated method-body attribution diagnostics may be
reported without failing only when they cannot affect any stored declaration
or type identity; this distinction is covered by fixtures rather than by
silently ignoring all attribution errors.

The parser records one entry for every top-level class, interface, record,
enum, and annotation interface, including multiple top-level declarations in
one file. It records direct fields, constructors, methods, record components,
superclass, interfaces, modifiers, parameter names and types, source path, and
exact source ranges. Nested declarations do not pollute their owner, while
their binary names remain available for semantic resolution. `package-info.java`
and `module-info.java` are parsed as inputs when needed but do not produce type
rows. Records, sealed declarations, annotations, generics, varargs, array
types, default interface methods, multiline declarations, compact constructors,
imports, wildcard imports, same-package references, and nested type references
are required test cases.

Source decoding is strict UTF-8. Malformed UTF-8, duplicate binary names,
syntax errors, and stored-identity resolution failures fail the index build.
Generated relative paths and row ordering are deterministic on Windows, Linux,
and macOS. The old regex backend is not available as a flag, fallback,
importer, or recovery path. There is no skip mode in the initial Java server.
All validation happens before H2 opens; the atomic writer composes schema,
exact row-count, and identity validation before promotion.

## Symbol Storage

The package JSON index is intentionally replaced by a normalized H2 MVStore symbol
database at `<index>/<version>/symbols.mv.db`. This removes thousands of JSON files
and prevents old regex-derived data from being silently treated as accurate.

The database contains a typed single-row metadata table plus normalized
packages, types, type interfaces, fields, methods, and parameters. Schema
version 1 is stored in metadata with the Minecraft version, source-root
`Path`, remapped-JAR SHA-256, and `TIMESTAMP WITH TIME ZONE` build instant.
Stable named primary, unique, check, and foreign-key constraints enforce the
complete schema. Package and type source identities use nullable public Fabric
API versions plus generated normalized keys so duplicate Minecraft packages
and namespace/version mismatches are impossible. Reopened validation checks
exact required columns and relevant types/nullability, generated expressions,
constraint names/columns/check clauses, foreign-key rules, secondary indexes,
metadata, and explicit orphan queries through H2 `INFORMATION_SCHEMA`. Source
text stays in the existing decompiled source tree and is read on demand.

Each database has a sibling application lock file. A configured lock timeout is
one monotonic deadline across local fair-lock acquisition, shared-state
coordination, OS-lock retries, and retry sleeps. Deadline conversion is
overflow-safe, and zero means one immediate nonblocking attempt. Queries take
a shared lock, open one short-lived H2 connection with `ACCESS_MODE_DATA=r` and
`IFEXISTS=TRUE`, close it promptly, then release the lock so Windows retains no
long-lived database handle. Writers wait up to 30 seconds for exclusive mode.
JDBC URLs stay inside the H2 boundary and use `DB_CLOSE_ON_EXIT=FALSE`,
`FILE_LOCK=FS`, `WRITE_DELAY=0`, `LOCK_TIMEOUT=30000`, and
`TRACE_LEVEL_FILE=0`. The same-directory temporary file is
`symbols.<pid>.tmp.mv.db`. One explicit transaction loads rows, creates
secondary indexes after bulk insertion, validates the complete schema/data,
commits, runs `CHECKPOINT SYNC`, closes H2, and forces the closed `.mv.db` with
`FileChannel.force(true)`. Closed validation rejects `.newFile`, `.tempFile`,
`.lock.db`, `.trace.db`, `.trace.db.old`, and numbered `<base>.<n>.temp.db`
companions before promotion.

`AtomicH2Database` is schema-neutral: callers supply the complete
`DatabaseValidator`; symbol builds explicitly compose `SymbolSchema.validate`,
while callgraph builds supply their own validator. Promotion first uses
`ATOMIC_MOVE` with `REPLACE_EXISTING`; no fallible work
runs after a successful atomic replacement. If atomic replacement is
unsupported, the fallback moves the current target to `symbols.mv.db.bak`,
moves the completed temporary file into place, reopens and validates the new
target, then deletes the backup. Recovery records the phase before every
non-atomic move and inspects the resulting target and backup state. It leaves
an original target byte-for-byte intact when that target remains, removes an
uncertain promoted target before one-way backup restoration, and never writes
a rejected target over a restored old database. If removal or restoration
cannot be established, it preserves the observed files with an actionable
error. Original failures remain primary and recovery or cleanup failures are
suppressed. Temporary cleanup never deletes a `.lock.db` companion, because it
may belong to an active H2 user. Startup restores `symbols.mv.db.bak` only
when the target is absent; when both exist it deletes the stale backup only if
the target validates, otherwise it preserves both with an actionable error.

Existing cache and source directories are retained. Legacy `manifest.json` and
package JSON indexes are detected but not imported because they may contain
regex-derived inaccuracies. A successful rebuild leaves those legacy files
untouched to avoid deleting user-modified cache data; the Java server ignores
them. `status` reports a legacy-only index as `needs rebuild`.
`MinecraftVersion` and `FabricApiVersion` remain public `String`-valued records
whose values are each validated once by a shared package-private portable
filename-component validator before they reach `Path.resolve`. The rule rejects
blank, dot, rooted or drive-relative, separator-containing, control-character,
Windows-reserved character/device-name (including legacy and superscript aliases
after Win32 basename trimming), and trailing-dot/space values regardless
of the host filesystem, while accepting ordinary Unicode and established
Minecraft/Fabric version forms. Record tests and `PlatformPaths` containment
tests keep this model boundary as the primary path-safety control.
`clean --index` takes the same exclusive application lock as rebuilds, refuses
an H2 `.lock.db` companion, and rejects a symlinked index root or symbol
database before lock/open. While holding the application lock, it takes H2's
whole-file exclusive `FILE_LOCK=FS` guard, removes legacy and companion
artifacts, rescans for `.lock.db`, and deletes the symbol database last while
the guard remains held. It retains the application lock pathname permanently
so competing processes never switch lock-file identities.
If cached sources exist, the diagnostic instructs the user to run `rebuild`;
no source redownload is required.

## Callgraph

The callgraph scanner opens the remapped Minecraft JAR with `ZipFile`, parses
class entries with `java.lang.classfile.ClassFile`, and writes directly through
JDBC. There is no cloned repository, external build, text report, worker JSON,
or whole-graph in-memory representation.

The output remains `<cache>/<version>/callgraph/callgraph.mv.db` with the existing
`calls` columns:

```text
id
caller_class
caller_method
caller_desc
callee_class
callee_method
callee_desc
line_number
```

The scanner records `invokevirtual`, `invokeinterface`, `invokestatic`, and
`invokespecial`, including constructors. For `invokedynamic`, it records a
target edge only when the bootstrap arguments identify a concrete method
handle, such as a lambda or method reference; string concatenation and unknown
bootstrap semantics do not create invented edges.

One `calls` row is stored for every qualifying bytecode invocation instruction.
Repeated invocations at the same or different bytecode offsets are not
deduplicated. This preserves call-site multiplicity and gives the stable `id`
column meaning as the final ordering tie-breaker.

Class names are normalized from JVM internal slash form to fully qualified dot
form before insertion. Method descriptors remain canonical JVM descriptors so
overloads stay distinguishable. The tool input continues to select by class
and method name and therefore returns every matching overload unless a future
MCP schema revision adds an optional descriptor filter.

Class parsing is bounded and parallel. One writer owns the JDBC connection,
inserts explicit batches inside a transaction, creates `idx_callee` and
`idx_caller` after insertion, validates the database, and atomically replaces
the previous file using the same lock, temporary-file, validation, and
promotion rules as `symbols.mv.db`. `line_number` is the original class file's
`LineNumberTable` call-site line for the invocation offset, or SQL `NULL` when
debug information is absent. It is not promised to match a line in
Vineflower's decompiled source.

`mc_find_refs` preserves callers/callees direction, class and method names,
descriptors, best-effort line numbers, a default limit of 100, and a hard
maximum of 5000. JDBC queries request the normalized limit plus one row so the
tool can report truncation without loading an unbounded result. Honoring values
through 5000 is an intentional correction to the current query's hidden SQL
cap of 100. Because the input schema has no descriptor, a query aggregates all
overloads matching the requested class and method name.

Caller results order by `caller_class`, `caller_method`, `caller_desc`,
`line_number`, then `id`; callee results use the corresponding callee columns
and the same final tie-breakers. User-visible method identities include the
canonical descriptor as `${className}.${methodName}${descriptor}` so overloads
are distinguishable. Existing Java-generated or java-callgraph2-generated
databases with the same schema remain readable. A legacy row with a null or
empty descriptor is returned without inventing one and sorts before a
non-empty descriptor for the same class and method.

## Decompilation And Remapping

The Java application retains Mojang manifest discovery, redirects, timeouts,
download progress, SHA-1/size validation when metadata provides it, corrupt-JAR
detection, official mappings, and cache paths.

Tiny Remapper and Vineflower run from shaded dependencies through narrow
adapters. Their current user-visible options remain unchanged. Tool JAR cache
entries may be read during transition but are no longer downloaded or required
after cutover.

Decompiler and remapper failures retain bounded output tails and identify the
stage, input, output, exit/API failure, and recovery command. Temporary outputs
are never promoted after failure.

## DebugBridge Boundary

DebugBridge remains a separate, version-specific mod and process. The stable
wire envelope is:

```json
{"id":"req_1","type":"snapshot","payload":{}}
{"id":"req_1","success":true,"result":{}}
```

The Java client initially owns small top-level envelope records matching
DebugBridge's `BridgeRequest` and `BridgeResponse`. Stable endpoint payloads
use top-level records converted through the raw `McpJsonMapper`; intentionally
dynamic Groovy or mod-defined results remain deeply immutable maps, lists,
primitives, or null. Request enums map their exact wire names explicitly rather
than relying on enum identifier serialization. Unknown fields are tolerated and
required fields are validated before tool handlers consume them.

Versioned request, success, error, missing-optional-field, and malformed
fixtures are captured from DebugBridge releases and stored under
`src/test/resources/debugbridge/<fixture-version>/`. Each fixture directory
identifies the DebugBridge commit/release and its observed capabilities. The Java client must pass the
same contract fixtures that DebugBridge serializes. Port scanning, reconnect
coalescing, request IDs, timeout ceilings, late responses, session identity,
and endpoint capability behavior remain compatible.

A tiny Java-17 `debugbridge-wire` artifact may be extracted from DebugBridge
after two independent fixture-backed releases. It may contain only the generic
envelope, wire version/capabilities, and JSON helpers. It must not pull Groovy,
Fabric, Minecraft, the web UI, or BridgeServer into mcdev-mcp. This extraction
is a later simplification, not a prerequisite for the Java rewrite.

## Packaging And Distribution

The primary release assets are:

```text
mcdev-mcp-<version>.jar
mcdev-mcp-<version>.jar.sha256
mcdev-mcp-<version>.mcpb
```

The executable JAR is the supported non-MCPB distribution. Users configure MCP
clients with `java -jar <path> serve`.

`gradle.properties` is the sole release-version authority. Gradle writes that
version to the JAR's `Implementation-Version`; CLI and MCP metadata read it from
the package manifest rather than duplicating a constant. MCPB metadata is
generated from a template plus the Java tool registry, never maintained as a
second hand-written catalog.

Shadow merges service descriptors with `mergeServiceFiles()`, removes stale
archive signatures under `META-INF/*.SF`, `META-INF/*.RSA`, and
`META-INF/*.DSA`, merges JDBC services, and preserves embedded resources
required by Tiny Remapper and Vineflower. Cross-platform tests run
from the extracted shaded JAR and prove `ServiceLoader<Driver>` and
`DriverManager` discovery plus H2 read/write/close under
`--illegal-native-access=deny`, a Tiny Remapper fixture, a Vineflower fixture, MCP STDIO
initialization, and the absence of signed-archive verification failures.

While MCPB manifest v0.4 remains Node-only, its staging tree contains the same
release JAR plus one minimal JavaScript bootstrap. The bootstrap validates
Java 25+, launches `java -jar ... serve` with inherited STDIO, forwards signals
and exit status, and contains no server logic. The packed MCPB is extracted and
smoke-tested under Node against the bundled JAR before release.

NPM publishing is retired at final cutover rather than used as a permanent Java
launcher distribution. Node and `@anthropic-ai/mcpb` remain packaging tools
only. The transition release documents the replacement GitHub Release and MCPB
installation paths before the npm package is deprecated.

Release CI builds the versioned JAR once on Java 25 and records its SHA-256.
Java 25 and Java 26 jobs download and test that exact artifact rather than
rebuilding it. The MCPB job also downloads the exact JAR artifact, verifies its
checksum, stages it with the generated manifest and launch shim, packs the
bundle, extracts it, and smoke-tests it. The GitHub Release job attaches only
the already-tested JAR, checksum, and MCPB, then verifies asset names and hashes
before publishing.

## Environment Compatibility

The Java server preserves these supported runtime switches and their existing
truthy/value semantics:

- `DEBUGBRIDGE_PORT`
- `MCDEV_RUN_COMMAND`
- `MCDEV_SCRIPT_LOGS`
- `MCDEV_MCP_DEBUG_LOG`

`MCDEV_MCP_SKIP_SMOKE` remains a build-script-only escape hatch and is never a
server setting. The Java indexer adds one documented runtime control,
`MCDEV_INDEX_THREADS`, parsed as a bounded positive integer and otherwise
defaulting to available processors.

Backend and subprocess controls disappear with the implementations they select:

- `MCDEV_INDEXER`
- `MCDEV_AST_PARSER`
- `MCDEV_SUPPRESS_INDEXER_HINT`
- `MCDEV_JAVA_WORKER_COMMAND`
- `MCDEV_JAVA_WORKER_ARGS_JSON`
- `MCDEV_INDEX_WORKERS`
- `MCDEV_INDEX_BATCH_SIZE`
- `MCDEV_INDEX_WORKER_HEAP_MB`
- `MCDEV_INDEX_WORKER_RETRY_HEAP_MB`
- `MCDEV_INDEX_PARSE_WORKER_PATH`
- `MCDEV_INDEX_WORKER_MARKER`
- `MCDEV_INDEX_SINGLE_FILE_FALLBACK`
- `MCDEV_MCP_REMAPPER_HEAP`

Test-only `MCDEV_ARGV_CAPTURE` also disappears. `MCDEV_MCP_HOME` is currently
only printed by the bootstrap and does not override paths, so the rewrite does
not invent support for it. Cache locations remain the current env-paths
locations: `~/Library/Caches/mcdev-mcp` on macOS,
`${XDG_CACHE_HOME:-~/.cache}/mcdev-mcp` on Linux, and
`%LOCALAPPDATA%\mcdev-mcp\Cache` on Windows. In-process heap sizing uses normal
JVM options such as `JAVA_TOOL_OPTIONS` or an explicit `-Xmx`, not a remapper-
specific environment variable.

## Java Version And Performance Policy

The build emits Java 25 bytecode and uses no preview APIs. Runtime preflight
accepts Java 25 and later.

Correctness CI runs on Java 25 and Java 26. Java 26 is documented as preferred
only after a same-runner macrobenchmark measures both versions against the same
cached source tree and remapped JAR. The benchmark performs one warmup and five
measured runs for symbol indexing and callgraph generation, reports medians,
peak resident memory, edge/class throughput, GC, JDK vendor/version, and exact
JVM flags, and retains machine-readable results as CI artifacts.

The scheduled benchmark compares default G1 on Java 25 and 26 in the same job
with counterbalanced run order. A separate advisory comparison may test
Parallel GC for one-shot analysis. Java 26 becomes the documented preferred
performance runtime only after three consecutive scheduled comparisons satisfy
all of these rules: the geometric mean of Java 26 indexing and callgraph
throughput is at least 5% above Java 25; neither individual workflow regresses
by more than 2%; and Java 26 peak RSS is no more than 10% worse in either
workflow. Otherwise Java 25 and 26 remain supported peers and the documentation
makes no preferred-performance claim. Benchmark results are advisory rather
than release gates.

## Error Handling

- All long operations accept cancellation and preserve interruption.
- Downloads use timeouts, bounded redirects, checksums where available, and
  temporary files followed by atomic promotion.
- Index and callgraph builds never replace a valid database with partial data.
- H2 connections, compiler file managers, JARs, streams, and WebSockets use
  deterministic close paths.
- MCP handlers convert expected failures to `isError` tool results and keep the
  server alive.
- Fatal startup errors identify the required Java version and detected runtime.
- DebugBridge errors retain endpoint, request ID, timeout, and bounded payload
  context without logging secrets or unbounded binary data.

## Testing

The migration uses five layers:

1. Java unit tests for parsers, path rules, validation, formatting, and state
   machines.
2. Fixture integration tests for symbol databases, class-file callgraphs,
   DebugBridge wire messages, resources, and CLI output.
3. Differential tests that run the TypeScript and Java servers against the same
   MCP request corpus and normalize nondeterministic paths, timestamps, ports,
   and ordering only where the existing contract allows it.
4. Packed-artifact tests that launch the shaded JAR directly and through the
   extracted MCPB Node bootstrap on Windows, Linux, and macOS.
5. Live DebugBridge smoke tests for endpoints whose behavior cannot be proven
   from fixtures, following the existing dev-loop guidance.

Project STDIO integration tests cover initialize, list/read/call, malformed
input, handler errors, and clean shutdown. Official server conformance v0.1.16
runs only against the shared-registry test HTTP harness described above.
Callgraph fixtures include repeated invocations, every supported opcode,
resolvable and unresolvable `invokedynamic`, overloaded methods, constructor
calls, missing `LineNumberTable`, legacy empty descriptors, both directions,
limit 100, limit 5000, and limit-plus-one truncation.

Full-corpus qualification indexes complete Minecraft 1.21.11 and 26.1 source
trees against their matching remapped JARs. It accounts for every discovered
Java compilation unit, including type-free `package-info.java` and
`module-info.java`, and permits no skipped or partially indexed files. Runs on
Java 25 and 26 with one and up to four workers must produce the same ordered
logical database hash. The qualification records immutable input hashes,
source/unit/type/member counts, peak live heap, peak RSS, and diagnostics while
running under a fixed 4 GiB maximum heap. The untouched Node oracle supplies
baseline counts and representative signatures, but Java corrections are
reviewed and documented rather than rejected merely because an inaccurate
legacy count differs.

Every migrated behavior starts with a failing Java test. The early cutover
removes legacy source before parity is complete, so later differential checks
must use the immutable original checkout as their oracle. Every task also
leaves Gradle warning-clean under `-Xlint:all -Werror` and has
clean IntelliJ MCP build and changed-file diagnostics without reformatting
unrelated source.

SDK mapper contract tests cover top-level records, enums, `URI`, unknown-field
tolerance, generic collections, large numbers, `Duration`, and `Instant`.
Generic tool-binding tests prove one whole-map conversion into a typed argument
record, explicit wire-to-domain mapping, bounded conversion failures, and raw
map use only for a deliberately open payload. A dependency/archive test rejects
Gson classes and direct Jackson implementation/annotation imports. `Path`
coverage uses explicit contract text conversion and never generic JSON
serialization.

## Acceptance Criteria

- `java -jar mcdev-mcp-<version>.jar serve` passes project STDIO integration
  tests, and the shared-registry HTTP harness passes official server
  conformance v0.1.16.
- Every current MCP tool and resource is present with compatible schemas and
  user-visible behavior.
- All CLI commands and documented environment switches work from the JAR.
- Java compiler APIs are the only production source parser; no regex parser,
  TypeScript AST parser, or parser fallback remains.
- A parse error fails atomically and leaves the prior symbol database intact.
- Complete Minecraft 1.21.11 and 26.1 corpus qualification passes without
  fallback, skipped files, worker-count drift, Java-version drift, or exceeding
  the fixed qualification heap cap; reviewed legacy-count corrections are
  recorded in the acceptance evidence.
- The Class-File API generator fully satisfies `mc_find_refs` callers and
  callees, including descriptors and best-effort line numbers.
- java-callgraph2 and its clone/build/output parser are absent.
- Runtime tools pass versioned DebugBridge fixture tests and live smoke tests.
- Java 25 and Java 26 correctness matrices pass.
- Java 25 versus 26 indexing and callgraph benchmark results are published as
  CI artifacts, and preferred-runtime guidance follows the three-run threshold
  rather than a single favorable result.
- GitHub Releases attach the shaded JAR, checksum, and packed MCPB.
- Daily Gradle and GitHub Actions dependency updates remain enabled; builds use
  exact reviewed coordinates, contain no unused dependency aliases, and keep
  any conformance HTTP container out of production and release artifacts.
- Every release surface derives its version from `gradle.properties`, and the
  MCPB contains the checksum-verified JAR built once on Java 25.
- The MCPB contains only a minimal Node bootstrap plus the same Java JAR and
  passes an extracted-bundle smoke test.
- The SDK `McpJsonMapper` is the sole JSON implementation: Gson is absent from
  source, tests, the runtime graph, and the shaded JAR; application code has no
  direct Jackson implementation, annotation, or `JsonNode` dependency.
- TypeScript, Bun, Kotlin, Jest, ESLint, npm runtime dependencies, worker JSON
  protocols, package JSON index readers/writers, and obsolete Superpowers
  migration documents are absent after final cutover. Legacy cache data is
  ignored unless the user explicitly runs `clean --index`.
- Production Java follows the top-level-type and semantic-value conventions,
  uses the Java 25 instance entry-point syntax, preserves the established
  IntelliJ formatting, compiles with `-Xlint:all -Werror`, and has no actionable
  IntelliJ project or file diagnostics.

## Primary References

- MCP Java SDK 2.0.0: https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0
- MCP Java server documentation: https://java.sdk.modelcontextprotocol.io/latest/server/
- MCP conformance 0.1.16: https://github.com/modelcontextprotocol/conformance/releases/tag/v0.1.16
- Java Class-File API: https://docs.oracle.com/en/java/javase/26/vm/jvm-apis.html
- JDK 25 release: https://openjdk.org/projects/jdk/25/
- JDK 26 G1 throughput work: https://openjdk.org/jeps/522
- Gradle Java compatibility: https://docs.gradle.org/current/userguide/compatibility.html
- DebugBridge architecture: https://github.com/use-ai-for-mc/debugbridge/blob/main/mod/ARCHITECTURE.md
- DebugBridge request envelope: https://github.com/use-ai-for-mc/debugbridge/blob/main/mod/core/src/main/java/com/debugbridge/core/protocol/BridgeRequest.java
- DebugBridge response envelope: https://github.com/use-ai-for-mc/debugbridge/blob/main/mod/core/src/main/java/com/debugbridge/core/protocol/BridgeResponse.java
