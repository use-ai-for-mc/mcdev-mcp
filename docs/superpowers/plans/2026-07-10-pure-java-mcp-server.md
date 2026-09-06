# Pure Java MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace mcdev-mcp's TypeScript/Node server and Java worker processes with one Java 25 shaded executable JAR while preserving the complete MCP, CLI, static-analysis, DebugBridge, packaging, and release behavior.

**Architecture:** One Gradle multi-project build contains the root application and an internal `mcp-tool-api` Java library; the root still produces the only shaded server and release JAR. The application owns the official MCP Java SDK STDIO server, Picocli CLI, Javac source indexer, Class-File API callgraph scanner, H2 MVStore storage, embedded Tiny Remapper/Vineflower pipeline, and JDK WebSocket DebugBridge client. The untouched `master` checkout at commit `7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6` is the Node parity oracle; tests materialize that commit into ignored scratch inside the isolated Java worktree, so neither `master` nor its working tree is modified. The early worktree cutover removes all legacy server source, worker code, and root Node toolchain before parity completes; later differential tests use the pinned oracle rather than restoring those files. MCPB retains only a minimal JavaScript launcher around the exact release JAR when packaging work begins.

> **Early cutover amendment (2026-07-12):** The isolated Java worktree removes the retired server, worker, root Node metadata, and Node CI before the later parity/release tasks. `cutoverCheck` is the tracked-file guard for this state. The original clean `master` checkout remains the immutable Node oracle; later tasks must materialize or invoke it from ignored scratch and must not restore legacy source in this worktree.

**Tech Stack:** Java 25 language/bytecode, Gradle Wrapper 9.6.1, Shadow 9.5.1, MCP Java SDK 2.0.1-SNAPSHOT with its Jackson 3-backed `McpJsonMapper`, Picocli 4.7.7, H2 2.4.240, Tiny Remapper 0.10.4, Vineflower 1.11.2, JUnit 6.1.0, Java Class-File API, Javac compiler APIs, JDK HttpClient/WebSocket.

## Global Constraints

- Work only in `C:\Users\ttski\Projects\mcdev-mcp\.worktrees\java25-indexer-callgraph` on `codex/java25-indexer-callgraph`; never edit, commit, switch, reset, clean, or build in the original `master` checkout.
- Treat `7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6` from the clean original checkout as the immutable Node parity oracle. Materialize it under ignored `.superpowers/parity/node-oracle/` before building or running it.
- Preserve the stash named `preserve-bun-ts7-dependency-experiment-before-java-runtime-task`; it is not part of this rewrite and must not be applied or dropped.
- Compile with a Java 25 toolchain and `options.release = 25`; reject runtime Java below 25 before downloads or cache mutation. Java 26 is a supported correctness runtime and becomes the documented performance preference only after the benchmark policy passes.
- Use final Java 25 language features where they simplify the code. The executable entry point is an instance `void main(String[] arguments)` method under the Java 25 launch protocol, not the legacy `public static void main(String[] arguments)` incantation.
- Keep production classes, interfaces, records, and enums top-level by default. Nest only a tiny private detail that is inseparable from its owner or a type whose SDK contract requires nesting; reusable domain values and cross-task interfaces get focused files of their own.
- Use designated Java/JDK domain types at boundaries: `URI`, `Path`, `Duration`, `Instant`, enums, or validated value records instead of unvalidated strings for URIs, paths, timeouts, timestamps, modes, and similar closed concepts. Protocol text, identifiers, MIME types, descriptors, and other open vocabularies remain strings.
- Use `McpJsonDefaults.getMapper()` as the sole JSON implementation. Do not add Gson, direct Jackson APIs/annotations, `JsonNode`, or a second JSON engine. The MCP transport alone wraps the raw mapper in `NodeParityJsonMapper`; metadata, tool arguments, tests, and DebugBridge receive the raw SDK interface.
- SDK 2.0 exposes `CallToolRequest.arguments()` as `Map<String,Object>` and typed conversion through `McpJsonMapper.convertValue`; do not build a parallel typed-getter facade. A thin generic `ToolBinding<A>` plus `ArgumentDecoder<A>` converts the complete map into a top-level per-tool record before `ToolHandler<A>` runs. Explicit decoders map wire-only primitives into `URI`, `Path`, `Duration`, `Instant`, enums, or validated domain records wherever structured alternatives exist. Raw maps/lists/primitives remain only for genuinely open JSON payloads.
- Put the extraction-ready SDK/JDK typed tool contracts in the internal `mcp-tool-api` `java-library` subproject under `dev.mcdevmcp.mcp.tool.api`. Java JSON type tokens, typed raw JSON, whole-map argument decoding, content, ordinary results, and generic structured results belong to the child; it has no root-project dependency and cannot import application types. `ToolBinding`, cancellation, executor policy, catalogs, transport adaptation, and Minecraft behavior remain in the root application. The child is the explicit module `dev.mcdevmcp.mcp.tool.api` and uses the reviewed build-scoped MCP SDK descriptor transform. Do not create or publish a separate repository during this rewrite; reconsider extraction or an upstream SDK proposal only after multiple static and runtime tool families prove the API and error model.
- Never serialize `Path` implicitly through a JSON mapper. Encode the exact contract-defined URI or path text explicitly and parse it at the boundary.
- Preserve the current IntelliJ-established Java formatting and follow its surrounding code style in every new edit. Do not run broad reformatting, rewrite unrelated whitespace, or introduce a competing formatter as part of feature work.
- Compile every Java source set with `-Xlint:all -Werror`. Before each task review, run IntelliJ MCP `build_project` and `get_file_problems` for every changed Java file, fix actionable errors and warnings, and keep any unavoidable suppression narrow and documented.
- Use the official MCP Java SDK 2.0.1-SNAPSHOT over production STDIO. Do not add Spring, Ktor, Kotlin, GraalVM native-image, preview JDK APIs, or another production transport.
- Declare dependencies only when a source set uses them. Keep exact reviewed versions so a commit resolves reproducibly, and let the existing daily Dependabot Gradle and GitHub Actions updates propose newer versions for review; do not use dynamic selectors, open version ranges, or stale unused catalog aliases. Any HTTP container remains test-only and must be absent from `runtimeClasspath` and `shadowJar`.
- Production remains STDIO-only because the frozen TypeScript server has no SSE or Streamable HTTP surface. Task 13's URL-based HTTP server is a test-only conformance harness over the same registry, not a second production transport.
- Use `McpServer.async(...)`. Internal handlers return JDK `CompletionStage`, Reactor remains confined to `McpSdkAdapter`, DebugBridge calls stay nonblocking, and H2/filesystem handlers use a Java 25 virtual-thread executor with cancellation propagation.
- Javac compiler/tree APIs are the sole production source parser. No regex parser, `java-parser`, TypeScript AST parser, parser fallback, parser importer, or skip mode may remain. User-requested regex matching in `mc_search` is search behavior, not source parsing, and remains compatible.
- Use `java.lang.classfile` directly for callgraph generation. Do not clone, build, execute, parse output from, or depend on java-callgraph2.
- Preserve all baseline MCP tools, including `mc_record_video`, both resources, CLI commands, exit codes, environment gates, defaults, limits, truncation text, error text, and output formatting unless the approved design explicitly corrects them.
- `mc_find_refs` intentionally fixes the hidden 100-row SQL cap: default 100, maximum 5000, query `limit + 1`, deterministic ordering, all overloads when no descriptor is supplied, canonical descriptors in displayed identities, one row per invocation, and nullable classfile source lines.
- Keep DebugBridge as a separate Minecraft mod process. The first Java implementation uses local envelope records and fixtures from DebugBridge v2.0.0 commit `72902e65c4edd1e2147dc6ac3f8182abd56711a1`; do not depend on DebugBridge's `core` module.
- The shaded JAR is the only server implementation and primary runtime artifact. MCPB is a separate distribution containing that exact JAR plus one packaging-only Node launcher with no server behavior.
- `gradle.properties` is the sole version authority. JAR metadata, CLI version, MCP server info, generated MCPB metadata, checksums, and release filenames derive from it.
- Every behavior change follows red-green-refactor: write a focused failing test, observe the expected failure, add the minimum production behavior, then run the focused and relevant aggregate suites.
- The legacy Jest suite currently aborts before running tests because `ts-jest` reads an incompatible TypeScript 6 API (`fileExists` is undefined). The user explicitly deferred that unrelated baseline error. Oracle guard tests use Node's built-in test runner and differential process tests; do not repair or rely on Jest during Task 1.
- Execute tasks sequentially with a fresh implementer and independent reviewer per task. Use task briefs, report files, commit-range review packages, and `.superpowers/sdd/progress.md`; no next task starts with an open Critical or Important finding.
- Model selection must conserve usage while matching risk. Use the stated recommendation as a ceiling unless the controller identifies added complexity: Luna medium for mechanical fixture/build edits, Terra medium/high for integration, Sol high only for compiler/classfile semantics and the final whole-branch audit. Reviewers use the least costly model that can judge the actual diff, with Terra as the floor for nontrivial prose-to-code work.
- Do not publish, create a release, deprecate npm, merge, or modify `master` during implementation. Release workflows may be implemented and dry-run; any external publication requires a separate explicit user action.

---

## Target File Map

The final application uses these ownership boundaries. Tasks may add private helpers beside the named files, but public cross-task interfaces must retain the signatures defined below.

```text
build.gradle.kts
settings.gradle.kts
gradle.properties
gradle/libs.versions.toml
mcp-tool-api/build.gradle.kts
mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/binding/
  ArgumentDecoder.java, package-info.java
mcp-tool-api/src/test/java/dev/mcdevmcp/mcp/binding/
  ArgumentDecoderTest.java
src/main/java/dev/mcdevmcp/
  app/Main.java, McdevCommand.java, McdevVersionProvider.java,
      ServeCommand.java, InitCommand.java, CallgraphCommand.java,
      RebuildCommand.java, StatusCommand.java, CleanCommand.java,
      AnalysisPipeline.java
  mcp/McpServerFactory.java
  mcp/transport/McpSdkAdapter.java, StdioServer.java,
      NodeParityJsonMapper.java, EofTrackingInputStream.java,
      NonClosingOutputStream.java
  mcp/tool/ToolCatalog.java, ToolDefinition.java, ToolAvailability.java,
      ToolHandler.java, BlockingToolHandler.java, ToolHandlers.java,
      ToolBinding.java, ToolMetadata.java, ToolResult.java,
      ToolContent.java, ToolContentType.java
  mcp/resource/ResourceCatalog.java, ResourceDefinition.java,
      ResourceRead.java
  analysis/classfile/ClassFileType.java, ClassFileTypeCatalog.java,
      ClassDescriptors.java
  analysis/index/SourceIndexer.java, IndexRequest.java, IndexSummary.java,
      IndexBuildException.java, SourceRoot.java, SourceIdentity.java
  analysis/index/pipeline/SourceIndexPipeline.java, JavacSourceParser.java,
      JavacPreflight.java, JavacBatchParser.java, JavacDeclarationReader.java,
      JavacDiagnostics.java, JavacTaskExecutor.java, TypeResolver.java,
      ParsedType.java, ParsedField.java, ParsedMethod.java,
      ParsedParameter.java, SymbolIndexWriter.java
  analysis/callgraph/CallgraphScanner.java, CallgraphRequest.java,
      CallgraphSummary.java, InvocationExtractor.java, CallEdge.java,
      CallgraphWriter.java
  analysis/decompile/VersionManifestClient.java, DownloadService.java,
      MappingConverter.java, MinecraftRemapper.java, MinecraftDecompiler.java
  storage/PlatformPaths.java
  storage/h2/DatabaseLock.java, AtomicH2Database.java, DatabaseBuilder.java,
      H2DatabaseArtifacts.java, H2DatabasePromotion.java, DatabaseQuery.java,
      DatabaseValidator.java, SymbolSchema.java,
      SymbolRepository.java, CallgraphSchema.java, CallgraphRepository.java,
      VersionStateRepository.java, IndexCleaner.java
  storage/model/*.java
  bridge/BridgeRequest.java, BridgeResponse.java, BridgeJson.java,
      BridgeClient.java, BridgeSession.java, BridgeProbe.java,
      BridgePayloadValidator.java
  tools/statictool/*.java
  tools/runtime/*.java
  support/AppEnvironment.java, AppVersion.java, DebugLog.java,
      ProgressSink.java, JsonValues.java, JsonResourceReader.java,
      Cancellation.java
src/main/resources/
  mcp/tools.json
  guides/python-scripting.md
  guides/dev-loop.md
src/test/java/dev/mcdevmcp/**
src/test/resources/contracts/**
src/test/resources/debugbridge/2.0.0/**
conformance/src/main/java/dev/mcdevmcp/conformance/ConformanceServerMain.java
benchmark/src/main/java/dev/mcdevmcp/benchmark/AnalysisBenchmarkMain.java,
    BenchmarkResult.java, BenchmarkDecision.java,
    BenchmarkComparisonRun.java, BenchmarkPolicy.java
packaging/mcpb/bootstrap.cjs
packaging/mcpb/manifest.template.json
packaging/mcpb/package.json
packaging/mcpb/package-lock.json
scripts/verify-release-assets.ps1
.github/workflows/ci.yml
.github/workflows/benchmark.yml
.github/workflows/release.yml
```

## Task 1: Freeze The Untouched Node Contract Oracle

**Recommended agent:** `gpt-5.6-luna`, medium reasoning. This is bounded fixture generation and guardrail work; a larger model would waste usage.

**Files:**
- Create: `contracts/node-oracle.json`
- Create: `scripts/materialize-node-oracle.mjs`
- Create: `scripts/capture-node-contracts.mjs`
- Create: `tests/contract-baseline.test.mjs`
- Create: `src/test/resources/contracts/mcp/initialize.json`
- Create: `src/test/resources/contracts/mcp/tools-list-default.json`
- Create: `src/test/resources/contracts/mcp/tools-list-dev.json`
- Create: `src/test/resources/contracts/mcp/resources-list.json`
- Create: `src/test/resources/contracts/mcp/resource-python-scripting.json`
- Create: `src/test/resources/contracts/mcp/resource-dev-loop.json`
- Create: `src/test/resources/contracts/cli/help.txt`
- Create: `src/test/resources/contracts/cli/version.txt`
- Create: `src/main/resources/mcp/tools.json`
- Modify: `.gitignore`
- Modify: `manifest.json`
- Modify: `package.json`

**Interfaces:**
- Consumes: clean `master` checkout discovered from `git worktree list --porcelain`; required oracle SHA `7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6`.
- Produces: immutable JSON/text fixtures; `npm run oracle:capture`; `src/main/resources/mcp/tools.json` as the union metadata catalog; an MCPB manifest that contains the same public tool names, including `mc_record_video`.

- [ ] **Step 1: Write the failing oracle guard and catalog tests**

Add a `node:test` test that reads `contracts/node-oracle.json`, verifies the exact SHA, verifies the default/dev tool snapshots, and compares the union against both `src/main/resources/mcp/tools.json` and `manifest.json`:

```ts
import assert from 'node:assert/strict';
import test from 'node:test';

const ORACLE_SHA = '7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6';

test('contract oracle is pinned and catalogs include record video', () => {
  assert.equal(oracle.commit, ORACLE_SHA);
  assert.ok(devTools.map(t => t.name).includes('mc_record_video'));
  assert.deepEqual(
    [...new Set(devTools.map(t => t.name))],
    productionMetadata.map(t => t.name));
  assert.deepEqual(manifestTools, productionMetadata.map(t => t.name));
});
```

- [ ] **Step 2: Run the test and observe the missing-fixture/manifest failure**

Run: `node --test tests/contract-baseline.test.mjs`

Expected: FAIL because the oracle metadata and snapshots do not exist and `manifest.json` omits `mc_record_video`.

- [ ] **Step 3: Implement a read-only oracle materializer**

`scripts/materialize-node-oracle.mjs` must:

```js
export const ORACLE_SHA = '7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6';
export const SCRATCH = '.superpowers/parity/node-oracle';
```

1. Parse `git worktree list --porcelain` and locate the clean worktree whose branch is `refs/heads/master` and whose HEAD equals `ORACLE_SHA`.
2. Refuse to proceed if that worktree is dirty or is the current worktree.
3. Remove only the resolved scratch directory after proving it is inside the current worktree's `.superpowers/parity` directory.
4. Run `git clone --local --no-hardlinks <master-worktree> <scratch>` and `git -C <scratch> checkout --detach ORACLE_SHA`.
5. Run `npm ci` and `npm run build` only in `<scratch>`.
6. Write no file in the original checkout.

Add `.superpowers/` to `.gitignore`.

- [ ] **Step 4: Capture protocol and CLI fixtures from the materialized oracle**

`scripts/capture-node-contracts.mjs` must spawn `<scratch>/dist/cli.js serve`, speak newline-delimited MCP JSON-RPC, and normalize only request IDs, absolute cache paths, timestamps, and ephemeral ports. Capture initialize, default `tools/list`, dev-enabled `tools/list` with `MCDEV_SCRIPT_LOGS=1` and `MCDEV_RUN_COMMAND=1`, resources list/read, `--help`, and `--version`. Write the union of dev-enabled tool metadata to `src/main/resources/mcp/tools.json` without handlers or availability state.

Add these temporary migration scripts to the root `package.json`:

```json
{
  "oracle:capture": "node scripts/capture-node-contracts.mjs",
  "oracle:test": "node --test tests/contract-baseline.test.mjs"
}
```

Write `contracts/node-oracle.json` exactly as:

```json
{
  "branch": "master",
  "commit": "7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6",
  "capturedAt": "2026-07-10",
  "mutableCheckoutUsed": false
}
```

- [ ] **Step 5: Reconcile `manifest.json` with the authoritative union**

Add `mc_record_video` with the exact description and schema from `tools-list-dev.json`. Preserve all existing MCPB user configuration. Sort manifest tool names in the same order as the Java metadata resource; do not alphabetize if that changes `tools/list` order.

- [ ] **Step 6: Run the contract tests and prove `master` stayed clean**

Run:

```powershell
npm run oracle:capture
node --test tests/contract-baseline.test.mjs
git -C C:\Users\ttski\Projects\mcdev-mcp status --short --branch
```

Expected: Node test PASS; original checkout prints only `## master...origin/master`.

- [ ] **Step 7: Commit the frozen contract**

```powershell
git add .gitignore contracts scripts tests/contract-baseline.test.mjs src/main/resources/mcp src/test/resources/contracts manifest.json package.json
git commit -m "test: freeze Node server parity contract"
```

## Task 2: Establish The Root Java 25 Application Build

**Recommended agent:** `gpt-5.6-terra`, medium reasoning. The edit is mostly build integration, but shading and source-set boundaries need more judgment than mechanical transcription.

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `src/main/java/dev/mcdevmcp/app/Main.java`
- Create: `src/main/java/dev/mcdevmcp/app/McdevCommand.java`
- Create: `src/main/java/dev/mcdevmcp/support/AppVersion.java`
- Create: `src/main/java/dev/mcdevmcp/support/AppEnvironment.java`
- Create: `src/main/java/dev/mcdevmcp/support/DebugLog.java`
- Create: `src/main/java/dev/mcdevmcp/support/ProgressSink.java`
- Create: `src/main/java/dev/mcdevmcp/support/Cancellation.java`
- Create: `src/test/java/dev/mcdevmcp/app/MainTest.java`
- Create: `src/test/java/dev/mcdevmcp/support/AppVersionTest.java`
- Create: `src/test/java/dev/mcdevmcp/packaging/ShadedJarSmokeTest.java`

**Interfaces:**
- Consumes: final Node-line version `2.2.1` from current `package.json`; rewrite release version `3.0.0`; pinned dependency versions from the approved design.
- Produces: `dev.mcdevmcp.app.Main`; `AppVersion.current()`; executable `build/libs/mcdev-mcp-3.0.0.jar`; Java 25 runtime preflight; reusable `ProgressSink`.

- [ ] **Step 1: Write failing version, runtime, and shaded-JAR tests**

Use these public contracts:

```java
public final class AppVersion {
    public static String current();
}

@FunctionalInterface
public interface ProgressSink {
    void report(String stage, int percent, String message);
}

@FunctionalInterface
public interface Cancellation {
    boolean isCancelled();
    default void throwIfCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Operation cancelled");
        }
    }
    static Cancellation none() { return () -> false; }
}

public record AppEnvironment(Map<String, String> values) {
    public static AppEnvironment system();
    public Optional<String> value(String name);
    public boolean isTruthy(String name);
    public Optional<Path> debugLogPath();
    public int indexThreads(int availableProcessors);
}
```

Tests assert `AppVersion.current()` is `3.0.0` when run from the JAR manifest, `Main --version` exits 0 with `3.0.0`, Java feature values below 25 are rejected before command execution, and the shaded JAR contains `Main-Class` plus `Implementation-Version`.

`DebugLog` tests preserve the existing `MCDEV_MCP_DEBUG_LOG` rules: unset,
empty, or `off` disables logging; `on` selects `/tmp/mcdev-debug.log`; any
other non-empty value is the file path. A logging failure is swallowed and no
debug message is written to protocol STDOUT.

- [ ] **Step 2: Run the Java test command and observe that the root build is absent**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.app.MainTest" --console=plain`

Expected: FAIL because the root wrapper/build and Java classes do not exist.

- [ ] **Step 3: Create the pinned version catalog and Gradle application**

`gradle/libs.versions.toml` must pin:

```toml
[versions]
mcp = "2.0.1-SNAPSHOT"
picocli = "4.7.7"
h2 = "2.4.240"
vineflower = "1.11.2"
tiny-remapper = "0.10.4"
junit = "6.1.0"
shadow = "9.5.1"
slf4j = "2.0.16"
```

Use `io.modelcontextprotocol.sdk:mcp`, `info.picocli:picocli`, `com.h2database:h2`, `org.vineflower:vineflower`, `net.fabricmc:tiny-remapper`, and `org.slf4j:slf4j-nop`. The SDK's transitive Jackson 3-backed `McpJsonMapper` is the sole JSON engine; do not declare Gson or Jackson directly. Configure Java toolchain 25, `options.release = 25`, UTF-8, JUnit Platform, application main class, and Shadow. Shadow must call `mergeServiceFiles()`, exclude `META-INF/*.SF`, `META-INF/*.RSA`, and `META-INF/*.DSA`, emit no classifier, and set manifest entries from `project.version` without native-access grants.

Set `gradle.properties` to:

```properties
version=3.0.0
group=dev.mcdevmcp
org.gradle.configuration-cache=true
org.gradle.parallel=true
```

- [ ] **Step 4: Install the 9.6.1 root wrapper and executable metadata**

Copy the already verified 9.6.1 wrapper files from `java-worker/` to the root, then run:

```powershell
git update-index --add --chmod=+x gradlew
```

Verify `gradle/wrapper/gradle-wrapper.properties` points to `gradle-9.6.1-bin.zip` and `git ls-files -s gradlew` begins with mode `100755`.

- [ ] **Step 5: Implement the entry point and Java preflight**

`Main` exposes an instance `void main(String[] arguments)` under the Java 25 launch protocol. It delegates through a testable `execute(...)` method to a Picocli `McdevCommand`, exits with Picocli's exit code, and checks `Runtime.version().feature() >= 25` before constructing commands that can download or mutate caches. Do not add a legacy `public static void main(...)`. `AppVersion.current()` reads `Main.class.getPackage().getImplementationVersion()` and falls back to the Gradle-filtered `/version.properties` only in test/classes execution. No version literal may appear in Java.

- [ ] **Step 6: Run focused tests, the full root build, and the shaded JAR**

Run:

```powershell
.\gradlew.bat clean test shadowJar --console=plain
java -jar build\libs\mcdev-mcp-3.0.0.jar --version
git ls-files -s gradlew
```

Expected: `BUILD SUCCESSFUL`; JAR prints `3.0.0`; wrapper mode is `100755`.

- [ ] **Step 7: Commit the application foundation**

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat src/main src/test
git commit -m "build: establish Java 25 MCP application"
```

## Task 3: Implement The MCP STDIO Shell, Catalog, And Resources

**Recommended agent:** `gpt-5.6-terra`, high reasoning. Official SDK 2.0 integration, exact protocol behavior, and STDOUT hygiene are cross-cutting.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolAvailability.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolDefinition.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolHandler.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/BlockingToolHandler.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolHandlers.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolBinding.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ArgumentDecoder.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolMetadata.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolContentType.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolContent.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolResult.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ToolCatalog.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ResourceDefinition.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ResourceRead.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ResourceCatalog.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/McpSdkAdapter.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/NodeParityJsonMapper.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/McpServerFactory.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/StdioServer.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/EofTrackingInputStream.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/NonClosingOutputStream.java`
- Create: `src/main/java/dev/mcdevmcp/app/ServeCommand.java`
- Create: `src/main/java/dev/mcdevmcp/app/McdevVersionProvider.java`
- Create: `src/main/java/dev/mcdevmcp/support/JsonValues.java`
- Create: `src/main/java/dev/mcdevmcp/support/JsonResourceReader.java`
- Create: `src/main/resources/guides/python-scripting.md`
- Create: `src/main/resources/guides/dev-loop.md`
- Create: `src/test/java/dev/mcdevmcp/mcp/ToolCatalogContractTest.java`
- Create: `src/test/java/dev/mcdevmcp/mcp/ToolBindingTest.java`
- Create: `src/test/java/dev/mcdevmcp/mcp/SdkJsonMapperTest.java`
- Create: `src/test/java/dev/mcdevmcp/packaging/GsonAbsenceTest.java`
- Create: `src/test/java/dev/mcdevmcp/mcp/ResourceCatalogTest.java`
- Create: `src/test/java/dev/mcdevmcp/mcp/McpStdioIntegrationTest.java`
- Modify: `src/main/java/dev/mcdevmcp/app/McdevCommand.java`

**Interfaces:**
- Consumes: `/mcp/tools.json`, contract fixtures, `AppEnvironment`, `AppVersion`, and the raw `McpJsonMapper` supplied by `McpJsonDefaults`.
- Produces: immutable `ToolCatalog`; generic SDK-mapper-backed `ToolBinding<A>`; async `ToolHandler<A>.handle(A, Cancellation)`; `McpServerFactory.startStdio(InputStream, OutputStream)` returning owned `StdioServer`; the production `serve` command and typed `ResourceRead` values.

- [ ] **Step 1: Write failing catalog, resource, and process-level STDIO tests**

Define the internal API exactly:

```java
public enum ToolAvailability { ALWAYS, SCRIPT_LOGS, RUN_COMMAND }

public enum ToolContentType { TEXT, IMAGE, AUDIO }

public record ToolDefinition(String name, String description,
        Map<String, Object> inputSchema, ToolBinding<?> binding,
        ToolAvailability availability) {}

public record ToolMetadata(String name, String description,
        Map<String, Object> inputSchema) {}

@FunctionalInterface
public interface ToolHandler<A> {
    CompletionStage<ToolResult> handle(
            A arguments, Cancellation cancellation);
}

@FunctionalInterface
public interface BlockingToolHandler<A> {
    ToolResult handle(A arguments, Cancellation cancellation)
            throws Exception;
}

@FunctionalInterface
public interface ArgumentDecoder<A> {
    A decode(McpJsonMapper mapper, Map<String, Object> arguments);
    static <A> ArgumentDecoder<A> sdk(Class<A> type);
    default <B> ArgumentDecoder<B> map(Function<A, B> mapper);
}

public final class ToolBinding<A> {
    public ToolBinding(ArgumentDecoder<A> decoder, ToolHandler<A> handler);
    CompletionStage<ToolResult> invoke(McpJsonMapper mapper,
            Map<String, Object> arguments, Cancellation cancellation);
}

public record ToolContent(ToolContentType type, String text,
        String mimeType, String data) {}

public record ToolResult(List<ToolContent> content, boolean isError) {
    public static ToolResult text(String text);
    public static ToolResult error(String text);
}

public record ResourceDefinition(URI uri, String name, String title,
        String description, String mimeType, String classpathResource) {}

public record ResourceRead(URI uri, String mimeType, String text) {}
```

`ArgumentDecoder.sdk(Class<A>)` delegates the complete map to the SDK mapper;
`map(...)` then converts a wire record into a domain record when units or wire
names differ. Per-tool wire records remain top-level and package-private. A
handler receives the domain record, so milliseconds become `Duration`, path
text becomes `Path`, URI text becomes `URI`, and closed strings become enums or
validated value records before business logic runs. Decoder failures name the
tool and bounded conversion error. A raw immutable map decoder is permitted
only for a field whose schema intentionally accepts arbitrary JSON.

Each declaration above is a top-level type in its own file. Compact
constructors validate required text, recursively freeze mutable JSON/list
inputs, and enforce the legal content shape. Do not replace `URI`, `Path`,
`Duration`, `Instant`, enums, typed argument strategies, or designated domain
values with strings or raw maps where a structured alternative exists.

Tests compare default and dev-enabled list responses byte-for-byte after JSON object-key normalization, assert both resources match the Node fixtures, assert an unknown tool returns `Unknown tool: <name>` with `isError=true`, and spawn the shaded JAR to verify initialize/list/read plus zero non-JSON bytes on STDOUT.

- [ ] **Step 2: Run the focused tests and observe missing MCP classes**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.mcp.*" --console=plain`

Expected: FAIL at compilation because the MCP catalog and server do not exist.

- [ ] **Step 3: Load exact metadata and bind availability without duplicating schemas**

`ToolCatalog` uses an injected raw `McpJsonMapper` and `JsonResourceReader` to deserialize the checked-in metadata into `ToolMetadata[]` in list order, recursively freezes each schema map, and attaches handlers by name. No Gson tree or mapper-to-mapper conversion exists. The two availability rules are Java-owned and exact:

```java
private static final Map<String, ToolAvailability> AVAILABILITY = Map.of(
    "mc_script_logs", ToolAvailability.SCRIPT_LOGS,
    "mc_run_command", ToolAvailability.RUN_COMMAND
);
```

All other tools are `ALWAYS`. `enabledDefinitions()` filters using the existing truthy values (`1` and `true`, case-insensitive, without trimming). Duplicate metadata, duplicate handlers, a handler without metadata, or malformed schema fails startup. During staged migration, metadata without a handler remains listable but dispatches a completed future containing the deterministic `Tool handler is not available in this migration build: <name>` error; Task 13 removes this transitional state and asserts complete binding.

- [ ] **Step 4: Implement the shared resource catalog**

Copy the baseline guide contents exactly into classpath resources. `ResourceCatalog` exposes only:

```text
mcdev://guides/python-scripting
mcdev://guides/dev-loop
```

Unknown URIs throw the same message as the Node fixture. Reads use UTF-8 and do not depend on the current working directory.

- [ ] **Step 5: Adapt the internal registry to the MCP SDK asynchronous server**

`McpServerFactory` creates one process-scoped raw mapper with `McpJsonDefaults.getMapper()` and injects it into the catalog and adapter. The production transport receives only `new NodeParityJsonMapper(rawMapper)`. Use `StdioServerTransportProvider(...)` and `McpServer.async(...)`. Server info is `mcdev-mcp` plus `AppVersion.current()`. Capabilities advertise tools and resources, instructions equal the baseline fixture, and tool input validation remains enabled. Pass the request's native argument map directly to `ToolBinding.invoke`, which decodes once and calls the typed handler; no serialization round-trip or field-by-field generic facade is allowed. Convert each handler stage with `Mono.fromFuture(stage.toCompletableFuture())`; Reactor types must not appear outside `McpSdkAdapter`. On Reactor cancellation, set the request's `Cancellation` signal and cancel the underlying future. Convert expected synchronous throws and exceptional completions into `ToolResult.error("Error executing " + name + ": " + message)` without terminating the process.

`ToolHandlers.blocking(ExecutorService, BlockingToolHandler)` adapts H2,
filesystem, and other blocking work with `Executors.newVirtualThreadPerTaskExecutor()`.
`ToolHandlers.completed(...)` covers immediate catalog/resource responses.
The factory owns and closes the virtual-thread executor with the async server.
Bridge handlers in later tasks return their natural `CompletionStage` directly
and never occupy a virtual thread while waiting on WebSocket I/O.

The adapter writes protocol messages only to its supplied output stream. `DebugLog` writes only to the configured file; all other diagnostics use the supplied STDERR stream. Add an integration test where one handler returns an incomplete future while a second request completes, then cancel the first and assert its cancellation signal/future are cancelled. This is the concurrency contract that justifies the async server choice.

If SDK 2.0 forces wire-visible behavior that differs from the frozen Node
contract, adapt typed SDK response objects in `NodeParityJsonMapper`; do not
parse or mutate serialized JSON strings. The mapper remains a narrowly tested
compatibility boundary and must not grow server business logic.

All production and test JSON reads/writes use only the `McpJsonMapper`
interface. Do not import Jackson implementation APIs or annotations. Permanent
mapper tests pin record, enum, `URI`, unknown-field, generic collection, large
number, `Duration`, and `Instant` behavior. Paths are tested through explicit
text parsing and are never passed to `writeValueAsString`.

- [ ] **Step 6: Run contract and process-level tests**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.mcp.*" --tests "dev.mcdevmcp.packaging.GsonAbsenceTest" shadowJar --console=plain
```

Expected: all MCP tests PASS and the spawned process returns valid initialize, tools, resources, and error responses.

Run `dependencyInsight` and inspect the shaded archive. Expected: no direct or
transitive Gson dependency, no `com/google/gson/` class, and no production or
test import of Gson, Jackson implementation APIs, annotations, or `JsonNode`.

Run IntelliJ MCP `build_project` and `get_file_problems` for every changed Java
file after the Gradle command. Expected: successful project build and no
actionable errors or warnings. Preserve the current IntelliJ formatting while
fixing diagnostics; do not reformat unrelated files.

- [ ] **Step 7: Commit the MCP shell**

```powershell
git add build.gradle.kts gradle/libs.versions.toml src/main/java/dev/mcdevmcp/mcp src/main/java/dev/mcdevmcp/app src/main/java/dev/mcdevmcp/support src/main/resources/guides src/test/java/dev/mcdevmcp/mcp src/test/java/dev/mcdevmcp/packaging
git commit -m "feat: add Java MCP STDIO shell"
```

## Task 3 Amendment: Adopt And Verify MCP SDK 2.0.1-SNAPSHOT

Before storage work, adopt the official `io.modelcontextprotocol.sdk` 2.0.1-SNAPSHOT from only `https://central.sonatype.com/repository/maven-snapshots/`. Configure that repository in `settings.gradle.kts` with `snapshotsOnly()` and an `includeGroup("io.modelcontextprotocol.sdk")` content filter; do not add a project repository or expose other dependency groups to snapshots.

The last reviewed upstream source is commit `fd004989b9484c9b81be6b03463396797b354804`, and the verified publication is `2.0.1-20260710.155611-10`. Its runtime graph resolves Jackson core and databind 3.1.4 and NetworkNT JSON Schema Validator 3.0.6, removing the actionable Jackson 3.0.3 security warning without direct Jackson, NetworkNT, Gson, alternate JSON-engine, or stable-constraint declarations.

Because the upstream snapshot is mutable, retain `mcpSdkSnapshotCheck` in Gradle `check` and make full CI run it. The task resolves `runtimeClasspath` and rejects any drift from all three official SDK modules at 2.0.1-SNAPSHOT, Jackson core/databind 3.1.4, NetworkNT 3.0.6, or the appearance of any Gson module. Refresh dependencies and review a changed upstream graph before accepting it; the tripwire is not a substitute for full Java 25/26 CI and shaded-JAR verification.

No broader typed-getter abstraction is justified: `McpSchema.CallToolRequest.arguments()` remains `Map<String,Object>`, and `McpJsonMapper` is API-identical to SDK 2.0.0. Keep production and tests behind `McpJsonMapper`, retain the existing whole-map typed `ArgumentDecoder`/tool-record boundary, and do not import Jackson implementation APIs, annotations, or `JsonNode`. The package-organization amendment moves that existing decoder into the internal `mcp-tool-api` Gradle library so its dependency direction is enforced; this is extraction preparation, not a second argument model.

The snapshot also carries useful future Streamable HTTP/stateless parity fixes: stateless initialized and roots notifications are handled without warning, unregistered stateless methods return JSON-RPC method-not-found responses, Streamable HTTP responses close after method-not-found, and completions with no handler return an empty result. These benefits do not add a production transport in this amendment.

## Task 4: Build Cross-Platform Paths And Atomic H2 Storage

**Recommended agent:** `gpt-5.6-terra`, high reasoning. Cross-process locks, Windows handles, crash recovery, and durable promotion are subtle integration work.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/storage/PlatformPaths.java`
- Create: `src/main/java/dev/mcdevmcp/storage/DatabaseLock.java`
- Create: `src/main/java/dev/mcdevmcp/storage/AtomicH2Database.java`
- Create: `src/main/java/dev/mcdevmcp/storage/DatabaseBuilder.java`
- Create: `src/main/java/dev/mcdevmcp/storage/DatabaseQuery.java`
- Create: `src/main/java/dev/mcdevmcp/storage/DatabaseValidator.java`
- Create: `src/main/java/dev/mcdevmcp/storage/SymbolSchema.java`
- Create: `src/main/java/dev/mcdevmcp/storage/SymbolRepository.java`
- Create: `src/main/java/dev/mcdevmcp/storage/VersionStateRepository.java`
- Create: `src/main/java/dev/mcdevmcp/storage/IndexCleaner.java`
- Create: `src/main/java/dev/mcdevmcp/storage/model/ClassSymbol.java`
- Create: `src/main/java/dev/mcdevmcp/storage/model/FieldSymbol.java`
- Create: `src/main/java/dev/mcdevmcp/storage/model/MethodSymbol.java`
- Create: `src/main/java/dev/mcdevmcp/storage/model/ParameterSymbol.java`
- Create: `src/test/java/dev/mcdevmcp/storage/PlatformPathsTest.java`
- Create: `src/test/java/dev/mcdevmcp/storage/AtomicH2DatabaseTest.java`
- Create: `src/test/java/dev/mcdevmcp/storage/SymbolSchemaTest.java`
- Create: `src/test/java/dev/mcdevmcp/storage/DatabaseLockProcessTest.java`

**Interfaces:**
- Consumes: OS environment and JDBC.
- Produces: `PlatformPaths.forEnvironment(Map<String,String>)`; `DatabaseLock.read/write`; `AtomicH2Database.rebuild`; schema version 1; short-lived read-only `SymbolRepository` queries; legacy index detection/cleaning.

- [ ] **Step 1: Write failing platform, schema, lock, recovery, and promotion tests**

Use these cross-task signatures:

```java
public record PlatformPaths(Path cacheRoot) {
    public static PlatformPaths forEnvironment(String osName, Map<String, String> env, Path home);
    public Path versionCache(MinecraftVersion version);
    public Path sourceRoot(MinecraftVersion version);
    public Path remappedJar(MinecraftVersion version);
    public Path symbolDatabase(MinecraftVersion version);
    public Path callgraphDatabase(MinecraftVersion version);
}

public final class AtomicH2Database {
    public <T> T rebuild(Path target, Duration lockTimeout,
                         DatabaseBuilder<T> builder,
                         DatabaseValidator validator) throws IOException, SQLException;
}

@FunctionalInterface
public interface DatabaseBuilder<T> {
    T build(Connection connection) throws Exception;
}

@FunctionalInterface
public interface DatabaseValidator {
    void validate(Connection connection) throws Exception;
}
```

`DatabaseBuilder`, `DatabaseQuery`, and `DatabaseValidator` are top-level interfaces in their own
files. Storage APIs carry filesystem locations and lock deadlines as `Path`
and `Duration`; they do not accept string encodings of either value.

Within that root preserve the current layout: Minecraft sources at
`cache/<version>/client`, obfuscated/unobfuscated JARs at
`cache/<version>/jars/`, the remapped callgraph input and database at
`cache/<version>/callgraph/client-remapped.jar` and `callgraph.mv.db`, Fabric
sources at `cache/fabric-api-<fabric-api-version>/`, and the new symbol database at
`index/<minecraft-version>/symbols.mv.db`.

`MinecraftVersion` and `FabricApiVersion` remain public `String`-valued records,
but each value must pass one shared, package-private portable filename-component
validator before any `Path.resolve` call. Reject blank, dot, rooted or
drive-relative, separator-containing, control-character, Windows-reserved
character/device-name (including legacy and superscript aliases after Win32
basename trimming), and trailing-dot/space values independently of the host
OS; retain ordinary Unicode and real version forms such as `1.21.5` and
`0.120.0+1.21.5`. Tests cover both records and prove accepted values remain
within the intended `PlatformPaths` cache and index roots.

Tests assert exact macOS, Linux/XDG, and Windows roots; typed schema-v1
metadata; complete H2 columns, constraints, indexes, and orphan validation;
Minecraft/Fabric package/type identity; overlapping local and subprocess
readers; actionable 30-second and injected short lock timeouts; stage-aware
fallback restoration; fixed and numbered companion rejection; single-file
close; and immediate Windows rename after a query closes.

- [ ] **Step 2: Run storage tests and observe missing implementations**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.storage.*" --console=plain`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement platform paths and explicit test injection**

Production roots are:

```text
macOS:   ~/Library/Caches/mcdev-mcp
Linux:   ${XDG_CACHE_HOME:-~/.cache}/mcdev-mcp
Windows: %LOCALAPPDATA%\mcdev-mcp\Cache
```

Do not add `MCDEV_MCP_HOME`. Tests and parity processes inject `PlatformPaths` through constructors; subprocess tests isolate Node by setting standard OS cache variables, not a new server environment switch.

- [ ] **Step 4: Implement schema version 1**

Create a typed single-row `metadata` table and normalized `packages`, `types`,
`type_interfaces`, `fields`, `methods`, and `parameters`. Store schema version
`1`, Minecraft version, source-root `Path`, remapped-JAR SHA-256, and a
`TIMESTAMP WITH TIME ZONE` build instant. Use stable named primary, unique,
check, and foreign-key constraints. A generated non-null normalized Fabric key
backs package uniqueness and the composite package/type source-identity
relationship while the public Fabric API version remains nullable. Minecraft
must have no Fabric version; Fabric must have one. `types.kind` accepts
`class`, `interface`, `enum`, `record`, and `annotation`. Reopened validation
uses H2 `INFORMATION_SCHEMA` to check exact required columns and relevant
types/nullability, generated expressions, constraints and key columns, check
clauses, foreign-key rules, required secondary indexes, metadata, and every
orphan relationship.

- [ ] **Step 5: Implement lock and crash-safe promotion**

Combine a fair process-local `ReentrantReadWriteLock` with a sibling OS lock;
same-process readers share one reference-counted OS lock. Readers use one
short-lived H2 connection with `ACCESS_MODE_DATA=r;IFEXISTS=TRUE`. Writer URLs
use `DB_CLOSE_ON_EXIT=FALSE;FILE_LOCK=FS;WRITE_DELAY=0;LOCK_TIMEOUT=30000;TRACE_LEVEL_FILE=0`.
One configured duration is a monotonic deadline spanning local and shared-state
locks, OS-lock retries, and retry sleeps. Deadline conversion is overflow-safe,
and zero makes one immediate nonblocking attempt.
Build same-directory `<base>.<pid>.tmp.mv.db` in one transaction, create
secondary indexes after load, validate, commit, run `CHECKPOINT SYNC`, close,
and force the file. Reject fixed and numbered H2 companions before promotion.
Try `ATOMIC_MOVE | REPLACE_EXISTING`; on unsupported atomic replacement, move
target to `.bak`, move temp to target, reopen/validate, and delete backup.
Before each non-atomic fallback move, record its phase and recover from the
observed target/backup state rather than trusting move-return flags. Preserve
an original target if it remains, remove an uncertain promoted target before
one-way backup restoration, and never write a rejected target over a restored
old database. Retain the observed files with an actionable failure when safe
recovery cannot be established. Preserve the original failure and suppress
restoration and cleanup failures. Temporary
cleanup refuses `.lock.db` rather than deleting an active or uncertain H2
companion. `AtomicH2Database` invokes only its supplied validator; symbol
builders explicitly compose `SymbolSchema.validate`. Startup restores `.bak` only when
target is absent and validates target before deleting a coexisting backup.

- [ ] **Step 6: Implement legacy detection and cleaning without implicit deletion**

`VersionStateRepository` reports H2-ready, legacy-only `needs rebuild`,
source-only, and absent. A successful H2 rebuild leaves legacy JSON untouched.
`IndexCleaner.cleanIndex(version)` rejects a symlinked version-index root or
symbol database before lock/open, takes the same exclusive application lock as
rebuilds, then takes H2's whole-file exclusive `FILE_LOCK=FS` guard. It never
deletes an encountered `.lock.db`, removes other H2 and legacy artifacts,
rescans, and deletes the `.mv.db` last while the guard remains held. It never
unlinks the application lock pathname, which is retained permanently to
preserve cross-process lock identity.

- [ ] **Step 7: Run focused and full storage tests**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.storage.*" --console=plain
```

Expected: all storage tests PASS, including subprocess lock and rename checks.

- [ ] **Step 8: Commit storage infrastructure**

```powershell
git add src/main/java/dev/mcdevmcp/storage src/test/java/dev/mcdevmcp/storage
git commit -m "refactor: use pure Java H2 storage"
```

## Task 5: Replace Every Source Parser With The Javac Indexer

**Recommended agent:** `gpt-5.6-sol`, high reasoning. Compiler attribution, binary identity, source ranges, deterministic parallelism, and failure classification justify the strongest model; lowering effort here risks restoring the accuracy problem this rewrite exists to remove.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/analysis/index/IndexRequest.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/IndexSummary.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/SourceRoot.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/SourceIndexer.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/IndexBuildException.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/ClassFileTypeCatalog.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/JavacSourceParser.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/TypeResolver.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/ParsedType.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/ParsedField.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/ParsedMethod.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/ParsedParameter.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/index/SymbolIndexWriter.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/index/JavacSourceParserTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/index/TypeResolutionTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/index/SourceIndexerIntegrationTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/index/IndexerFailureAtomicityTest.java`
- Create: `src/test/resources/indexer/sources/**`

**Interfaces:**
- Consumes: `AtomicH2Database`, `SymbolSchema`, typed Minecraft/Fabric source roots, the matching remapped JAR, optional classpath JARs, `MCDEV_INDEX_THREADS`, `ProgressSink`, `Cancellation`.
- Produces: schema-v1 `symbols.mv.db`; deterministic `IndexSummary`; exact stored declaration identities/ranges; no parser fallback.

- [ ] **Step 1: Write failing syntax, semantic identity, declaration, and atomicity tests**

Define the public request/result API:

```java
public record IndexRequest(
        MinecraftVersion minecraftVersion,
        List<SourceRoot> sourceRoots,
        Path remappedJar,
        List<Path> classpath,
        Path outputDatabase,
        int threads,
        ProgressSink progress,
        Cancellation cancellation) {}

public record SourceRoot(
        SourceNamespace namespace,
        Optional<FabricApiVersion> fabricApiVersion,
        Path path) {
    public SourceRoot {
        if (namespace == SourceNamespace.MINECRAFT && fabricApiVersion.isPresent()) {
            throw new IllegalArgumentException("Minecraft source roots must not have a Fabric API version");
        }
        if (namespace == SourceNamespace.FABRIC && fabricApiVersion.isEmpty()) {
            throw new IllegalArgumentException("Fabric source roots must have a Fabric API version");
        }
    }
}

public record IndexSummary(
        int packages,
        int types,
        int fields,
        int methods,
        int parameters,
        Duration elapsed) {}

public final class SourceIndexer {
    public IndexSummary build(IndexRequest request) throws IndexBuildException;
}

public final class IndexBuildException extends Exception {
    public IndexBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Fixtures must cover multiple top-level types, classes, interfaces, enums, records, annotation interfaces, sealed declarations, compact constructors, default interface methods, record components, generic bounds, arrays, varargs, multiline declarations, multiple declarators, nested declarations, imported/wildcard/same-package/nested hierarchy names, source-only attributed types, `package-info.java`, `module-info.java`, malformed UTF-8, duplicate binary names, ambiguous imports, syntax errors, unrelated method-body attribution errors, and a prior valid DB surviving every failing build.

- [ ] **Step 2: Run indexer tests and observe missing production types**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.analysis.index.*" --console=plain`

Expected: FAIL at compilation.

- [ ] **Step 3: Build the authoritative class-file type catalog**

Use `ZipFile` plus Java 25's finalized `ClassFile.of().parse(...)` over sorted `.class` entries. Store canonical dot-form binary names, superclass, direct interfaces, access flags, nesting metadata, and JDK descriptor values where faithful. Ignore `module-info.class`; retain nested names for resolver use. Reject duplicate binary entries. The remapped JAR catalog is authoritative for hierarchy identity whenever a source type has the same binary name. Do not use ASM.

- [ ] **Step 4: Parse strict UTF-8 with isolated Javac tasks**

Discover without following links and strictly decode every source with a `CharsetDecoder` configured with `CodingErrorAction.REPORT` before any compiler task. Sort by complete typed source identity and normalized relative path. Supply explicit and on-demand sources only from that validated in-memory corpus. Partition into bounded batches; each CPU-bound worker owns and closes its `StandardJavaFileManager` and `JavacTask`, and no `Tree`, `Element`, or `TypeMirror` escapes. Configure every typed root as `SOURCE_PATH`; configure `CLASS_PATH` from the exact remapped JAR followed by the copied request classpath. Use `-proc:none`, `-implicit:none`, and `-encoding UTF-8`, with class output confined to memory. Persist each row's typed source namespace and nullable Fabric API version.

Own each decoded source `String` once in the immutable corpus. Every worker's
file objects and binary-name aliases reference that shared text; worker count
must not multiply corpus text. Submit no more batches than the bounded worker
count, so active Javac state and queued/completed results have an explicit
upper bound. Modular corpora use one stable Javac task so `module-info.java`
cannot change ordinary-unit attribution according to batch placement.

`JavacSourceParser` visits only direct members of each top-level type, records `SourcePositions` start/end UTF-16 character offsets and line-map values, and emits one `ParsedType` per top-level declaration. Source reads decode the complete file and slice the Java `String`; they never treat Javac offsets as UTF-8 byte offsets. Nested types never become direct members of their owner. Package/module units emit no type row.

- [ ] **Step 5: Resolve stored semantic identities without guessing**

For catalog-backed types, join by fully qualified binary name and take superclass/interfaces from bytecode. For source-only types, call `JavacTask.analyze()`, resolve with `Trees.getElement(TreePath)` and `TypeMirror`, and canonicalize binary names through `Elements.getBinaryName(TypeElement)`. Fail on ambiguity or unresolved binary name, hierarchy, field type, return type, or parameter type. Allow an unrelated body diagnostic only when a test proves no stored identity or range depends on it; emit it through diagnostics.

- [ ] **Step 6: Merge deterministically and write atomically**

Merge worker results by typed source identity, normalized source path, and declaration offset. Check duplicate binary names before opening the output writer. `SymbolIndexWriter` inserts explicit deterministic JDBC batches in one transaction, creates secondary indexes afterward, and composes `SymbolSchema.validate` with exact count and identity checks through `AtomicH2Database`. Parse/attribution/cancellation failures leave the prior DB byte-for-byte unchanged.

Parse `MCDEV_INDEX_THREADS` as a positive integer clamped to `1..availableProcessors`; invalid values fail with the variable name and supplied value. Default to available processors.

- [ ] **Step 7: Prove determinism and absence of fallback**

Run the same fixture with thread counts 1 and 4, dump every table with explicit ordering, and assert identical content excluding `built_at`. Add a source that the old regex parser accepted incorrectly and assert the Javac build fails rather than producing a partial row. Search production Java for forbidden parser selectors.

Add regression coverage proving independent worker file objects return the
same decoded `CharSequence` instances, batch admission never exceeds the
effective worker count, and package/module-only units are accounted for even
when they emit no rows. Full Minecraft 1.21.11 and 26.1 corpus qualification is
the pre-release gate in Tasks 15 and 17 rather than a networked unit test.

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.analysis.index.*" --console=plain
rg -n "MCDEV_INDEXER|MCDEV_AST_PARSER|java-parser|parseJavaContent|regex backend" src/main/java
```

Expected: tests PASS; `rg` returns no matches.

- [ ] **Step 8: Commit the sole source indexer**

```powershell
git add src/main/java/dev/mcdevmcp/analysis/index src/test/java/dev/mcdevmcp/analysis/index src/test/resources/indexer
git commit -m "feat: index Java sources with Javac"
```

## Task 5A Amendment: Reorganize Java Packages And Files

The approved
[`2026-07-15-java-package-organization.md`](2026-07-15-java-package-organization.md)
plan is implemented and independently reviewed. It creates the internal
`mcp-tool-api` library, moves the current application into shallow
MCP/H2/class-file/index capsules, splits named top-level declarations and
concentrated coordinators, and adds the compiler-tree source-layout invariant.

This amendment is behavior-neutral. The root project remains the sole shaded
server/release artifact and a classpath application. The child JAR reserves
`dev.mcdevmcp.mcp.tool.api` through `Automatic-Module-Name`; neither project
adds `module-info.java` or patches the MCP SDK's invalid automatic module
metadata. Task 6 and every later task use the amended target file map above.

## Task 6: Port Symbol Queries And All Static MCP Tools

**Recommended agent:** `gpt-5.6-terra`, medium reasoning. The database boundary is defined; this is broad but contract-driven formatting and query work.

**Files:**
- Modify: `src/main/java/dev/mcdevmcp/storage/h2/SymbolRepository.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/StaticToolModule.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/StaticToolSupport.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McVersionTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McSearchTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McGetClassTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McGetMethodTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McListClassesTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McListPackagesTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McFindHierarchyTool.java`
- Create: `src/test/java/dev/mcdevmcp/tools/statictool/StaticToolContractTest.java`
- Create: `src/test/resources/contracts/static-tools/requests.jsonl`
- Create: `src/test/resources/contracts/static-tools/responses.jsonl`
- Modify: `src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java`

**Interfaces:**
- Consumes: schema-v1 symbol DB, decompiled source files, active/explicit version rules, baseline tool schemas and output corpus.
- Produces: handlers for `mc_version`, `mc_search`, `mc_get_class`, `mc_get_method`, `mc_list_classes`, `mc_list_packages`, and `mc_find_hierarchy`. `mc_find_refs` remains assigned to Task 7.

- [ ] **Step 1: Capture and write failing static-tool golden tests**

Build one deterministic fixture DB/source tree and run the Node oracle against equivalent legacy fixture data to capture requests for success, empty, malformed, missing-version, default-limit, explicit-limit, and truncated cases. The test invokes Java handlers and compares exact text/error output after replacing only fixture root paths.

Normalize limits through one API:

```java
public record LimitSpec(int defaultValue, int maximum) {
    public int normalize(OptionalInt requestedLimit);
}
```

Limit values, schema minima/maxima, result counts, and truncation notes must match the frozen metadata and Node responses.

- [ ] **Step 2: Run the static contract test and observe unbound handlers**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.tools.statictool.*" --console=plain`

Expected: FAIL because static handlers are absent.

- [ ] **Step 3: Implement short-lived, prepared symbol queries**

Add prepared queries for package listing, class listing, exact/simple class lookup, direct members, method lookup, hierarchy traversal, and search hits. Every query acquires a shared DB lock, opens read-only, uses explicit `ORDER BY`, applies `limit + 1`, maps rows, closes, then releases. Reject path traversal before reading source ranges. Preserve source snippets exactly from stored UTF-8 offsets.

- [ ] **Step 4: Implement the seven handlers and bind them by exact name**

`StaticToolModule.handlers()` returns:

```java
package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.storage.PlatformPaths;

import java.util.Map;

public final class StaticToolModule {
    private StaticToolModule() {
    }

    public static Map<String, ToolBinding<?>> handlers(PlatformPaths paths) {
        var support = new StaticToolSupport(paths);
        return Map.ofEntries(
                Map.entry("mc_version", McVersionTool.binding(support)),
                Map.entry("mc_search", McSearchTool.binding(support)),
                Map.entry("mc_get_class", McGetClassTool.binding(support)),
                Map.entry("mc_get_method", McGetMethodTool.binding(support)),
                Map.entry("mc_list_classes", McListClassesTool.binding(support)),
                Map.entry("mc_list_packages", McListPackagesTool.binding(support)),
                Map.entry("mc_find_hierarchy", McFindHierarchyTool.binding(support)));
    }
}
```

Bind these JDBC/filesystem handlers through `ToolHandlers.blocking(...)` so
their work runs on the factory-owned virtual-thread executor rather than a
Reactor transport thread. Preserve explicit-version precedence and current
active-version fallback. `mc_search` may compile a user-supplied regex only for
requested source searching; catch invalid patterns as tool errors and never use
that regex to infer declarations.

- [ ] **Step 5: Run focused tests plus MCP list/call integration**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.tools.statictool.*" --tests "dev.mcdevmcp.mcp.transport.McpStdioIntegrationTest" --console=plain
```

Expected: static golden corpus and MCP dispatch tests PASS.

- [ ] **Step 6: Commit static tools**

```powershell
git add src/main/java/dev/mcdevmcp/storage/h2/SymbolRepository.java src/main/java/dev/mcdevmcp/tools/statictool src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java src/test/java/dev/mcdevmcp/tools/statictool src/test/resources/contracts/static-tools
git commit -m "feat: port static analysis tools to Java"
```

## Task 7: Generate The Focused Class-File Callgraph And Complete `mc_find_refs`

**Recommended agent:** `gpt-5.6-sol`, high reasoning. Invocation semantics, `invokedynamic`, line mapping, multiplicity, and deterministic concurrent writes are correctness-critical and deserve the strongest reasoning allocation.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/analysis/callgraph/CallEdge.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/callgraph/CallgraphRequest.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/callgraph/CallgraphSummary.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/callgraph/InvocationExtractor.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/callgraph/CallgraphScanner.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/callgraph/CallgraphWriter.java`
- Create: `src/main/java/dev/mcdevmcp/storage/h2/CallgraphSchema.java`
- Create: `src/main/java/dev/mcdevmcp/storage/h2/CallgraphRepository.java`
- Create: `src/main/java/dev/mcdevmcp/storage/model/MethodReference.java`
- Create: `src/main/java/dev/mcdevmcp/tools/statictool/McFindRefsTool.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/callgraph/InvocationExtractorTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/callgraph/CallgraphScannerIntegrationTest.java`
- Create: `src/test/java/dev/mcdevmcp/storage/h2/CallgraphRepositoryTest.java`
- Create: `src/test/java/dev/mcdevmcp/tools/statictool/McFindRefsContractTest.java`
- Create: `src/test/resources/callgraph/**`
- Modify: `src/main/java/dev/mcdevmcp/tools/statictool/StaticToolModule.java`

**Interfaces:**
- Consumes: remapped JAR, atomic H2 infrastructure, frozen `mc_find_refs` schema.
- Produces: existing `calls` table DB; callers/callees repository queries; fully compatible `mc_find_refs` with intentional 5000-limit correction.

- [ ] **Step 1: Write failing opcode, dynamic-call, line, order, and limit tests**

Define exact records:

```java
public record CallEdge(
        String callerClass, String callerMethod, String callerDescriptor,
        String calleeClass, String calleeMethod, String calleeDescriptor,
        Integer lineNumber, long encounterOrder) {}

public record MethodReference(
        String className, String methodName, String descriptor,
        Integer lineNumber, long edgeId) {
    public String displayName() {
        return className + "." + methodName + (descriptor == null ? "" : descriptor);
    }
}

public record CallgraphRequest(
        String minecraftVersion, Path remappedJar, Path outputDatabase,
        int threads, ProgressSink progress, Cancellation cancellation) {}
```

Compile fixture classes that emit `invokevirtual`, `invokeinterface`, `invokestatic`, `invokespecial`, constructors, lambda/method-reference `invokedynamic`, string-concat `invokedynamic`, duplicate calls on one line, duplicate calls on different lines, overloaded targets, and a class compiled without debug lines. Add a legacy H2 fixture with null/empty descriptors. Assert one edge per qualifying instruction and no invented string-concat edge.

- [ ] **Step 2: Run callgraph tests and observe missing implementation**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.analysis.callgraph.*" --tests "dev.mcdevmcp.storage.h2.CallgraphRepositoryTest" --console=plain`

Expected: FAIL at compilation.

- [ ] **Step 3: Extract calls directly with the Java 25 Class-File API**

Open the JAR with `ZipFile`, sort `.class` entry names, and parse using `ClassFile`. For each method code model, track the current `LineNumberTable` line by bytecode position and emit an edge for every qualifying invoke instruction. Convert internal slash names to dots and preserve canonical JVM descriptors. Constructors remain `<init>`.

For `invokedynamic`, follow bootstrap arguments only when a concrete method handle identifies the target, including lambda/metafactory and method references. Ignore string concat and unknown bootstrap owners. Store SQL `NULL` when no line entry covers the callsite.

- [ ] **Step 4: Write the existing schema without whole-graph retention**

Use the existing columns `id`, `caller_class`, `caller_method`, `caller_desc`, `callee_class`, `callee_method`, `callee_desc`, `line_number`. Bounded parser workers emit sorted batches to one writer connection. Insert in deterministic class/method/bytecode order, create `idx_caller` and `idx_callee` after loading, validate counts/foreign keys/integrity, and promote `callgraph.<pid>.tmp.mv.db` to `callgraph.mv.db` with the common checkpoint, force, companion-validation, and stage-aware atomic/backup rules.

- [ ] **Step 5: Implement exact callers/callees queries and tool formatting**

Filter callers by callee class+method and callees by caller class+method; because input has no descriptor, aggregate every overload. Normalize requested limit to default 100 and maximum 5000, fetch one extra row, and set truncation from that extra row. Caller order is caller class/method/descriptor/line/id; callee order is corresponding callee fields/line/id. Render descriptors in identities. Return legacy null/empty descriptors without fabricating one.

- [ ] **Step 6: Run focused, limit-5000, deterministic, and legacy tests**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.analysis.callgraph.*" --tests "dev.mcdevmcp.storage.h2.CallgraphRepositoryTest" --tests "dev.mcdevmcp.tools.statictool.McFindRefsContractTest" --console=plain
```

Expected: all callgraph/ref tests PASS, including 5001-row truncation and missing-line cases.

- [ ] **Step 7: Prove java-callgraph2 is absent from the Java implementation**

Run: `rg -n "java-callgraph2|java-callgraph|javacg|callgraph\.txt" src/main/java build.gradle.kts gradle`

Expected: no matches.

- [ ] **Step 8: Commit callgraph and `mc_find_refs`**

```powershell
git add src/main/java/dev/mcdevmcp/analysis/callgraph src/main/java/dev/mcdevmcp/storage/h2 src/main/java/dev/mcdevmcp/storage/model/MethodReference.java src/main/java/dev/mcdevmcp/tools/statictool src/test/java/dev/mcdevmcp/analysis/callgraph src/test/java/dev/mcdevmcp/storage/h2/CallgraphRepositoryTest.java src/test/java/dev/mcdevmcp/tools/statictool/McFindRefsContractTest.java src/test/resources/callgraph
git commit -m "feat: generate callgraph with Class-File API"
```

## Task 8: Embed Download, Remap, Decompile, And CLI Pipelines

**Recommended agent:** `gpt-5.6-terra`, high reasoning. This task coordinates network integrity, two embedded libraries, cache state, CLI parity, cancellation, and atomic outputs.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/analysis/decompile/VersionManifestClient.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/decompile/DownloadService.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/decompile/MappingConverter.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/decompile/MinecraftRemapper.java`
- Create: `src/main/java/dev/mcdevmcp/analysis/decompile/MinecraftDecompiler.java`
- Create: `src/main/java/dev/mcdevmcp/app/AnalysisPipeline.java`
- Create: `src/main/java/dev/mcdevmcp/app/PreparedSources.java`
- Create: `src/main/java/dev/mcdevmcp/app/InitCommand.java`
- Create: `src/main/java/dev/mcdevmcp/app/CallgraphCommand.java`
- Create: `src/main/java/dev/mcdevmcp/app/RebuildCommand.java`
- Create: `src/main/java/dev/mcdevmcp/app/StatusCommand.java`
- Create: `src/main/java/dev/mcdevmcp/app/CleanCommand.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/decompile/DownloadServiceTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/decompile/MappingConverterTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/decompile/EmbeddedRemapperTest.java`
- Create: `src/test/java/dev/mcdevmcp/analysis/decompile/EmbeddedDecompilerTest.java`
- Create: `src/test/java/dev/mcdevmcp/app/CliContractTest.java`
- Create: `src/test/java/dev/mcdevmcp/app/AnalysisPipelineIntegrationTest.java`
- Modify: `src/main/java/dev/mcdevmcp/app/McdevCommand.java`

**Interfaces:**
- Consumes: Mojang manifests/download metadata, Tiny Remapper/Vineflower libraries, SourceIndexer, CallgraphScanner, platform paths/state.
- Produces: complete `serve/init/callgraph/rebuild/status/clean` CLI; remapped JAR and source cache; no downloaded tool JAR or analysis subprocess.

- [ ] **Step 1: Write failing download-integrity, embedded-tool, pipeline, and CLI golden tests**

Use an in-process test HTTP server for redirects, timeouts, known length/SHA-1, truncation, and corrupt ZIP responses. Build tiny input/mapping fixtures for Tiny Remapper and Vineflower. Compare command help, validation, progress (`[stage] N% - message`), success summaries, status states, and failures to the Node CLI fixtures.

The pipeline contract is:

```java
public final class AnalysisPipeline {
    public PreparedSources prepareSources(MinecraftVersion version, ProgressSink progress,
                                          Cancellation cancellation);
    public IndexSummary rebuildIndex(MinecraftVersion version, ProgressSink progress,
                                     Cancellation cancellation);
    public CallgraphSummary rebuildCallgraph(MinecraftVersion version, ProgressSink progress,
                                             Cancellation cancellation);
}

public record PreparedSources(
        MinecraftVersion minecraftVersion, List<SourceRoot> sourceRoots,
        Path obfuscatedJar,
        Path unobfuscatedJar, Path remappedJar) {}
```

- [ ] **Step 2: Run focused tests and observe missing pipeline classes**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.analysis.decompile.*" --tests "dev.mcdevmcp.app.CliContractTest" --console=plain`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement robust Mojang metadata and downloads**

Use one JDK `HttpClient` with bounded connect/request timeouts and at most five redirects. Stream to same-directory unique temp files, report progress, validate provided size and SHA-1, verify JARs with `ZipFile`, then atomically promote. Preserve valid cached inputs. Errors name URL, stage, target, expected/actual checksum or size, and recovery command without dumping unbounded bodies.

- [ ] **Step 4: Run Tiny Remapper and Vineflower in process**

Convert official ProGuard mappings to deterministic Tiny v2 input. Call Tiny Remapper APIs directly and close remapper/input/output resources. Call Vineflower through a narrow adapter with current user-visible options, capture bounded diagnostics, write to a temporary source tree, validate expected Java output, then promote. Do not execute `java`, download tool JARs, or honor `MCDEV_MCP_REMAPPER_HEAP`; heap guidance uses standard JVM flags.

- [ ] **Step 5: Implement all Picocli commands and lifecycle checks**

Register exactly:

```text
serve
init -v <version> [--skip-callgraph]
callgraph -v <version>
rebuild -v <version> [--with-callgraph]
status [-v <version>]
clean [--callgraph|--cache|--index|--all] [-v <version>]
```

Preserve the current version validator (Minecraft 1.14+ and modern 26.x-style versions), exit codes, progress and summary text. `status` distinguishes H2-ready, legacy-only `needs rebuild`, decompiled-only, and absent. `rebuild` reuses cached source/remapped JAR. Cancellation preserves valid previous files and thread interruption.

- [ ] **Step 6: Run embedded library, CLI, and full pipeline tests**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.analysis.decompile.*" --tests "dev.mcdevmcp.app.*" --console=plain
.\gradlew.bat shadowJar --console=plain
java -jar build\libs\mcdev-mcp-3.0.0.jar status
```

Expected: tests PASS; status exits cleanly; no external tool JAR is downloaded by fixtures.

- [ ] **Step 7: Commit embedded analysis and CLI**

```powershell
git add src/main/java/dev/mcdevmcp/analysis/decompile src/main/java/dev/mcdevmcp/app src/test/java/dev/mcdevmcp/analysis/decompile src/test/java/dev/mcdevmcp/app
git commit -m "feat: embed analysis pipeline and CLI"
```

## Task 9: Implement The DebugBridge Wire Boundary And Session

**Recommended agent:** `gpt-5.6-terra`, high reasoning. Concurrent request correlation, reconnect identity, timeouts, and malformed wire input need careful state-machine work, but the protocol itself is fixture-defined.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeRequest.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeEndpoint.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeResponse.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeWireResponse.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeJson.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeClient.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeSession.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgeProbe.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/BridgePayloadValidator.java`
- Create: `src/main/java/dev/mcdevmcp/bridge/SessionInfo.java`
- Create: `src/test/java/dev/mcdevmcp/bridge/BridgeJsonContractTest.java`
- Create: `src/test/java/dev/mcdevmcp/bridge/BridgeSessionTest.java`
- Create: `src/test/java/dev/mcdevmcp/bridge/BridgeProbeTest.java`
- Create: `src/test/java/dev/mcdevmcp/bridge/FakeDebugBridge.java`
- Create: `src/test/resources/debugbridge/2.0.0/metadata.json`
- Create: `src/test/resources/debugbridge/2.0.0/request.json`
- Create: `src/test/resources/debugbridge/2.0.0/success.json`
- Create: `src/test/resources/debugbridge/2.0.0/error.json`
- Create: `src/test/resources/debugbridge/2.0.0/missing-optional.json`
- Create: `src/test/resources/debugbridge/2.0.0/malformed.json`

**Interfaces:**
- Consumes: JDK `HttpClient.WebSocket`, the injected raw SDK `McpJsonMapper`, `DEBUGBRIDGE_PORT`, DebugBridge v2.0.0 envelope fixtures.
- Produces: typed local envelope; concurrent `BridgeSession.connect/send/reset/adoptPort`; status probes; tolerant JSON and strict consumed-field validation.

- [ ] **Step 1: Add exact v2.0.0 fixture provenance and failing wire/session tests**

`metadata.json` is exact:

```json
{
  "release": "v2.0.0",
  "commit": "72902e65c4edd1e2147dc6ac3f8182abd56711a1",
  "requestShape": ["id", "type", "payload"],
  "responseShape": ["id", "success", "result", "output", "error"]
}
```

Define envelopes:

```java
public record BridgeEndpoint(String wireName) {}
public record BridgeRequest(String id, BridgeEndpoint endpoint, Object payload) {}
public record BridgeResponse(
        String id, boolean success, Object result,
        String output, String error) {}
public record BridgeWireResponse(
        String id, Boolean success, Object result,
        String output, String error) {}

public final class BridgeSession implements AutoCloseable {
    public CompletionStage<SessionInfo> connect(Integer explicitPort);
    public CompletionStage<SessionInfo> adoptPort(int port);
    public CompletionStage<BridgeResponse> send(
            BridgeEndpoint endpoint, Object payload, Duration endpointTimeout);
    public OptionalInt connectedPort();
    public Optional<SessionInfo> sessionInfo();
    public void reset();
}
```

Tests cover exact serialization, unknown-field tolerance, missing required ID/success failure, malformed JSON, request IDs, 10-second default, caller timeout plus 5 seconds capped at 5 minutes, late responses, close rejection, concurrent send, one shared in-flight auto-connect, explicit-port behavior, reset, port scan 9876-9886, and warn-only game-directory mismatch.

- [ ] **Step 2: Run bridge tests and observe missing implementation**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.bridge.*" --console=plain`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement strict envelope/tolerant payload JSON**

Inject the same raw `McpJsonMapper` used by the application. `BridgeJson` maps `BridgeEndpoint.wireName()` explicitly into the request envelope, serializes top-level request payload records directly, reads `BridgeWireResponse`, validates required ID/success before correlation, recursively freezes open results, and then constructs `BridgeResponse`. Stable endpoint payloads/results use top-level records and `convertValue`; only intentionally dynamic script/mod payloads use open JSON maps/lists/primitives/null. `BridgePayloadValidator` exposes typed `requireResult(response, Class<T>)`, intentionally open `requireOpenObject`, primitive shape validation, safe bounded JSON display, and a 7 MiB base64 PNG text cap. Error messages match the Node `validate-resp` fixtures and include endpoint names. Do not import Gson, Jackson implementation APIs/annotations, or `JsonNode`.

- [ ] **Step 4: Implement JDK WebSocket request correlation and reconnects**

`BridgeClient` owns one WebSocket, an `AtomicLong` request counter, and a concurrent pending map keyed by `req_<n>`. Each pending request owns its timeout and is removed exactly once. Close rejects all pending calls. Malformed/unmatched messages are bounded diagnostics, not process failures.

`BridgeSession` coalesces only implicit concurrent connects, scans 11 ports from the configured base, issues `status` before accepting a connection, remembers connected port/session/game directory, permits intentional instance changes with a warning, and preserves auto-scan across `adoptPort`. Invalid `DEBUGBRIDGE_PORT` falls back to 9876 exactly as Node does.

- [ ] **Step 5: Run wire, concurrency, timeout, and probe tests**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.bridge.*" --console=plain`

Expected: all bridge tests PASS with no leaked pending requests or executor threads.

- [ ] **Step 6: Commit the DebugBridge boundary**

```powershell
git add src/main/java/dev/mcdevmcp/bridge src/test/java/dev/mcdevmcp/bridge src/test/resources/debugbridge
git commit -m "feat: add Java DebugBridge session"
```

## Task 10: Port Core Runtime Inspection And Execution Tools

**Recommended agent:** `gpt-5.6-terra`, medium reasoning. Endpoint mechanics are complete; this is a contract-driven group of related handlers and output validators.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolModule.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolSupport.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McConnectTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McExecuteTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McSnapshotTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McNearbyEntitiesTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McEntityDetailsTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McNearbyBlocksTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McBlockDetailsTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McLookedAtEntityTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McChatHistoryTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McScreenInspectTool.java`
- Create: `src/test/java/dev/mcdevmcp/tools/runtime/CoreRuntimeToolContractTest.java`
- Create: `src/test/resources/contracts/runtime-tools/core-requests.jsonl`
- Create: `src/test/resources/contracts/runtime-tools/core-bridge-responses.jsonl`
- Create: `src/test/resources/contracts/runtime-tools/core-tool-results.jsonl`
- Modify: `src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java`

**Interfaces:**
- Consumes: BridgeSession, payload validator, exact metadata and Node golden corpus.
- Produces: handlers for connect, execute, snapshot, nearby/detail entity/block, looked-at entity, chat history, and screen inspect.

- [ ] **Step 1: Capture endpoint-specific corpus and write failing handler tests**

The corpus must cover success, bridge-declared error, missing result, wrong primitive type, unknown added field, timeout, and disconnected reconnect. Map tools to wire endpoint exactly:

```text
mc_connect             -> status/connect behavior
mc_execute             -> execute
mc_snapshot            -> snapshot
mc_nearby_entities     -> nearbyEntities
mc_entity_details      -> entityDetails
mc_nearby_blocks       -> nearbyBlocks
mc_block_details       -> blockDetails
mc_looked_at_entity    -> lookedAtEntity
mc_chat_history        -> chatHistory
mc_screen_inspect      -> screenInspect
```

Golden results preserve whitespace, numeric formatting, JSON indentation, default values, limits, optional fields, and error prefixes.

- [ ] **Step 2: Run the contract test and observe unbound runtime handlers**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.tools.runtime.CoreRuntimeToolContractTest" --console=plain`

Expected: FAIL with missing handlers.

- [ ] **Step 3: Implement and bind the ten handlers**

Each handler constructs only the payload fields present in the request, calls the exact endpoint above, checks `success`, validates every consumed result field, tolerates unknown fields, and renders the frozen text. `mc_execute` preserves code/output/return/error behavior and timeout scaling. `mc_connect reset:true` clears prior instance identity before connecting.

Compose results with `CompletionStage` operations; do not call `join`, `get`,
or block a virtual thread while waiting for DebugBridge.

`RuntimeToolModule.handlers()` returns a mutable builder-backed map so Tasks 11 and 12 can add disjoint handler groups without editing these implementations.

- [ ] **Step 4: Run core runtime plus MCP dispatch tests**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.tools.runtime.CoreRuntimeToolContractTest" --tests "dev.mcdevmcp.mcp.transport.McpStdioIntegrationTest" --console=plain
```

Expected: all selected tests PASS.

- [ ] **Step 5: Commit core runtime tools**

```powershell
git add src/main/java/dev/mcdevmcp/tools/runtime src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java src/test/java/dev/mcdevmcp/tools/runtime src/test/resources/contracts/runtime-tools
git commit -m "feat: port core DebugBridge tools"
```

## Task 11: Port Runtime Media, Texture, And Glow Tools

**Recommended agent:** `gpt-5.6-terra`, medium reasoning. The shared bridge is stable; this task is mostly exact schema, binary-bound, timeout, and formatting parity.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McScreenshotTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McRecordVideoTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McGetItemTextureTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McGetEntityItemTextureTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McGetItemTextureByIdTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McSetEntityGlowTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McSetBlockGlowTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McClearBlockGlowTool.java`
- Create: `src/test/java/dev/mcdevmcp/tools/runtime/MediaRuntimeToolContractTest.java`
- Create: `src/test/resources/contracts/runtime-tools/media-requests.jsonl`
- Create: `src/test/resources/contracts/runtime-tools/media-bridge-responses.jsonl`
- Create: `src/test/resources/contracts/runtime-tools/media-tool-results.jsonl`
- Modify: `src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolModule.java`

**Interfaces:**
- Consumes: BridgeSession and shared response validation.
- Produces: screenshot/video, three texture, and three glow handlers with text/image MCP content.

- [ ] **Step 1: Write failing media golden and safety tests**

Map exact endpoints:

```text
mc_screenshot                 -> screenshot
mc_record_video               -> record_video
mc_get_item_texture           -> getItemTexture
mc_get_entity_item_texture    -> getEntityItemTexture
mc_get_item_texture_by_id     -> getItemTextureById
mc_set_entity_glow            -> setEntityGlow
mc_set_block_glow             -> setBlockGlow
mc_clear_block_glow           -> clearBlockGlow
```

Tests include screenshot path/base64 variants, texture image content, >7 MiB rejection, video grid/frames modes, numeric interval strings, unknown mode, dropped frames, malformed path arrays, and exact glow acknowledgements.

- [ ] **Step 2: Run the focused test and observe missing handlers**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.tools.runtime.MediaRuntimeToolContractTest" --console=plain`

Expected: FAIL with missing handlers.

- [ ] **Step 3: Implement exact image and recording semantics**

Texture tools return MCP image content with `image/png` and enforce the base64 bound before allocation/copy. Screenshot preserves baseline text/image ordering. `McRecordVideoTool` uses:

```java
static Duration recordingDeadline(int frames, OptionalLong intervalMillis) {
    long perFrame = intervalMillis.isPresent()
            ? Math.max(1L, intervalMillis.getAsLong()) : 17L;
    return Duration.ofMillis(
            Math.addExact(Math.multiplyExact(frames, perFrame), 15_000L));
}
```

Coerce finite numeric interval strings to numbers, pass all other values through for bridge validation, then rely on BridgeSession's five-minute ceiling. Render grid/frames outputs exactly as the golden files.

- [ ] **Step 4: Run media and aggregate runtime tests**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.tools.runtime.*" --console=plain
```

Expected: core and media runtime tests PASS.

- [ ] **Step 5: Commit media runtime tools**

```powershell
git add src/main/java/dev/mcdevmcp/tools/runtime src/test/java/dev/mcdevmcp/tools/runtime src/test/resources/contracts/runtime-tools
git commit -m "feat: port DebugBridge media tools"
```

## Task 12: Port Session Control And Environment-Gated Dev Tools

**Recommended agent:** `gpt-5.6-terra`, high reasoning. Polling state, stale-world avoidance, cross-platform PID probes, and gated registration are subtle enough for stronger reasoning.

**Files:**
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/SessionControlSupport.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McJoinServerTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McLeaveServerTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McWaitUntilInWorldTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McQuitClientTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McWaitForBridgeTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McRunCommandTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/McScriptLogsTool.java`
- Create: `src/main/java/dev/mcdevmcp/tools/runtime/ScriptLogger.java`
- Modify: `src/main/java/dev/mcdevmcp/tools/runtime/McExecuteTool.java`
- Create: `src/test/java/dev/mcdevmcp/tools/runtime/SessionControlSupportTest.java`
- Create: `src/test/java/dev/mcdevmcp/tools/runtime/SessionRuntimeToolContractTest.java`
- Create: `src/test/resources/contracts/runtime-tools/session-requests.jsonl`
- Create: `src/test/resources/contracts/runtime-tools/session-bridge-responses.jsonl`
- Create: `src/test/resources/contracts/runtime-tools/session-tool-results.jsonl`
- Modify: `src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolModule.java`

**Interfaces:**
- Consumes: BridgeSession/Probe, OS process probes, `MCDEV_RUN_COMMAND`, `MCDEV_SCRIPT_LOGS`.
- Produces: five always-listed session-control handlers and two conditionally listed dev handlers; exact launch/rejoin/quit safety behavior.

- [ ] **Step 1: Write failing state-machine, PID, gating, and golden tests**

Map wire endpoints:

```text
mc_join_server          -> snapshot, joinServer, snapshot, screenInspect
mc_leave_server         -> disconnect
mc_wait_until_in_world  -> snapshot, screenInspect
mc_quit_client          -> quit plus port/process exit probes
mc_wait_for_bridge      -> status probes
mc_run_command          -> runCommand (only MCDEV_RUN_COMMAND truthy)
mc_script_logs          -> local ScriptLogger (only MCDEV_SCRIPT_LOGS truthy)
```

Tests cover join from menu, join from an existing world requiring observed absence, disconnected screen failure, transient request failures, 1-second polling, 60/30/120-second defaults, matching game-directory/version, mismatched instances, configured out-of-range port, PID parse ambiguity, process alive/dead classification, port-close fallback, and both env-gated `tools/list` snapshots.

- [ ] **Step 2: Run session tests and observe missing implementation**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.tools.runtime.Session*" --console=plain`

Expected: FAIL at compilation or missing handlers.

- [ ] **Step 3: Implement deterministic polling and stale-world protection**

Model poll results as sealed records `Joined`, `Failed`, and `Pending`. A player-bearing snapshot counts as joined after an in-world join request only once at least one successful snapshot without a player has been observed. A disconnected screen fails immediately. Null/transient responses provide no state evidence. One deadline spans all polls.

Drive one-second polling with a scheduled executor and chained
`CompletionStage` operations. MCP cancellation must cancel the scheduled poll
and pending bridge future; no wait handler may sleep or block an SDK/virtual
thread between polls.

Scan 9876-9886 plus a valid configured port outside that range. Match game directory first, version second, any instance only when neither expected field exists. Report mismatches once per changed port description.

- [ ] **Step 4: Implement quit confirmation and dev gates**

Resolve the listener PID before sending quit: PowerShell `Get-NetTCPConnection` on Windows, `lsof -t -iTCP:<port> -sTCP:LISTEN` elsewhere, each with a 4-second bound. Parse exactly one positive PID. After quit, wait for port closure, then `ProcessHandle.of(pid).map(ProcessHandle::isAlive)` until the same deadline; degrade to port-only with an explicit unconfirmed note when PID resolution fails.

`McRunCommandTool` and `McScriptLogsTool` are bound always but filtered from `tools/list` and rejected by dispatch unless their environment gates are truthy. Script logs use the same path, retention, redaction, and output format as Node and never log code unless opted in.

Inject `ScriptLogger` into `McExecuteTool` in this task. When
`MCDEV_SCRIPT_LOGS` is truthy it records the same execution metadata and
bounded output as Node; when false it performs no file I/O. This completes the
environment-gated behavior that Task 10 intentionally left behind the shared
logger interface.

- [ ] **Step 5: Run every runtime and catalog contract test**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.tools.runtime.*" --tests "dev.mcdevmcp.mcp.tool.ToolCatalogContractTest" --console=plain
```

Expected: all runtime groups and both default/dev catalogs PASS.

- [ ] **Step 6: Commit session and dev tools**

```powershell
git add src/main/java/dev/mcdevmcp/tools/runtime src/test/java/dev/mcdevmcp/tools/runtime src/test/resources/contracts/runtime-tools
git commit -m "feat: port DebugBridge session tools"
```

## Task 13: Prove Full Node Parity And MCP Conformance

**Recommended agent:** `gpt-5.6-terra`, high reasoning. Differential normalization, process orchestration, exact handler completeness, and test-only HTTP reuse require broad integration judgment.

**Files:**
- Create: `src/test/java/dev/mcdevmcp/parity/NodeOracleMaterializer.java`
- Create: `src/test/java/dev/mcdevmcp/parity/McpProcessClient.java`
- Create: `src/test/java/dev/mcdevmcp/parity/DifferentialMcpTest.java`
- Create: `src/test/java/dev/mcdevmcp/parity/DifferentialCliTest.java`
- Create: `src/test/java/dev/mcdevmcp/parity/HandlerCompletenessTest.java`
- Create: `src/test/resources/contracts/parity/requests.jsonl`
- Create: `conformance/src/main/java/dev/mcdevmcp/conformance/ConformanceServerMain.java`
- Create: `conformance/src/main/java/dev/mcdevmcp/conformance/ConformanceServlet.java`
- Create: `src/main/java/dev/mcdevmcp/mcp/ServerDefinition.java`
- Create: `scripts/run-conformance.ps1`
- Modify: `build.gradle.kts`
- Modify: `src/main/java/dev/mcdevmcp/mcp/McpServerFactory.java`
- Modify: `src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java`

**Interfaces:**
- Consumes: pinned Node oracle materializer, complete Java handler registry, shared MCP server definition.
- Produces: differential MCP/CLI gates; zero missing handlers; test-only Streamable HTTP executable; official conformance 0.1.16 gate without production HTTP.

- [ ] **Step 1: Write failing handler-completeness and differential tests**

`HandlerCompletenessTest` loads every metadata entry and asserts exactly one bound handler, even when environment-gated. Remove the staged missing-handler dispatch path once this test exists.

The parity request corpus covers initialize, list tools/resources, both resource reads, unknown tool, malformed arguments, every static tool success/error/empty/truncated path, and every runtime handler against a scripted fake bridge. Differential CLI covers all commands, help, invalid versions, missing caches, legacy-only status, and clean selectors.

- [ ] **Step 2: Run completeness/parity tests and observe remaining drift**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.parity.*" --console=plain
```

Expected: FAIL until all handlers are bound and normalization/process orchestration is implemented.

- [ ] **Step 3: Materialize the original checkout without touching it**

`NodeOracleMaterializer` reads `contracts/node-oracle.json` and implements the complete workflow in Java with `ProcessBuilder`; it never delegates to a repository script. It parses `git worktree list --porcelain`, requires exactly one checkout on `refs/heads/master` at the pinned SHA, rejects the current Java worktree, and records that checkout's exact clean status before doing any work. It creates only ignored `.superpowers/parity/node-oracle/` scratch, verifies the resolved deletion/materialization path remains below `.superpowers/parity/`, then runs a local `git clone --no-hardlinks --no-checkout`, detached checkout of the pinned SHA, `npm ci`, and `npm run build` in that scratch clone. Node oracle process tests also run only from the scratch clone.

Remove inherited `npm_config_allow_scripts`, provide a scratch-local empty `NPM_CONFIG_USERCONFIG`, and fail closed on any command error. Java and Node processes receive separate temporary `LOCALAPPDATA`, `XDG_CACHE_HOME`, and `HOME` roots populated from the same immutable fixtures. A `finally` guard re-reads the original checkout's branch, HEAD, and porcelain status and requires byte-for-byte equality with the pre-test snapshot, including when materialization, build, or differential execution fails.

- [ ] **Step 4: Compare responses with a narrow normalizer**

Normalize only JSON-RPC IDs, absolute fixture roots, timestamps documented in the fixture, and dynamically assigned fake-bridge ports. Do not normalize list order, schemas, limits, descriptors, line numbers, error wording, whitespace inside tool text, or truncation. On mismatch, write request, Node response, Java response, and JSON-pointer/text diff under `build/reports/parity/`.

For approved intentional changes, Java-only assertions govern: server/artifact version is the rewrite release `3.0.0` rather than the Node oracle's `2.2.1`; schema-v1 H2 replaces package JSON; `mc_find_refs` honors 101..5000 and displays descriptors; legacy-only status says `needs rebuild`.

- [ ] **Step 5: Share one server definition with the test-only HTTP harness**

Refactor `McpServerFactory` so STDIO and conformance each build an async SDK
server from one immutable definition:

```java
public record ServerDefinition(
        String name, String version, String instructions,
        ToolCatalog tools, ResourceCatalog resources) {}
```

Add a `conformance` Gradle harness project. Prefer a supported container-free JDK
Streamable HTTP provider if the SDK exposes one when this task is implemented;
do not write a custom transport merely to avoid a test dependency. Otherwise
mount the SDK's built-in Streamable HTTP servlet with its upstream-tested
embedded container at `http://127.0.0.1:3000/mcp`. Add the Servlet/container
aliases only when this project uses them, select the then-current supported
and non-vulnerable release, commit the exact reviewed versions, and let the
existing daily Dependabot configuration maintain them. Dynamic version
selectors are forbidden. A dependency/archive test proves the container is
confined to the conformance configuration and absent from `runtimeClasspath`
and `shadowJar`.

- [ ] **Step 6: Run official conformance 0.1.16 and production STDIO integration**

`scripts/run-conformance.ps1` starts the test harness, waits for port 3000, runs:

```powershell
npx --yes @modelcontextprotocol/conformance@0.1.16 server --url http://127.0.0.1:3000/mcp --suite active
```

It always terminates the harness and propagates exit code. The build-only CI conformance job provisions Node 24 with `actions/setup-node`; Node and `npx` are test tooling only and never enter the Java runtime artifact. Also run the shaded JAR STDIO suite; official conformance is not described as STDIO coverage.

Run:

```powershell
.\gradlew.bat test parityTest shadowJar --console=plain
.\scripts\run-conformance.ps1
git -C C:\Users\ttski\Projects\mcdev-mcp status --short --branch
```

Expected: Java tests PASS; conformance PASS; original prints only clean master status.

- [ ] **Step 7: Commit parity and conformance gates**

```powershell
git add build.gradle.kts conformance src/main/java/dev/mcdevmcp/mcp src/test/java/dev/mcdevmcp/parity src/test/resources/contracts/parity scripts/run-conformance.ps1
git commit -m "test: prove Java server parity and conformance"
```

## Task 14: Package The Same JAR In MCPB With A Minimal Node Launcher

**Recommended agent:** `gpt-5.6-luna`, medium reasoning for the implementer and `gpt-5.6-terra`, medium reasoning for review. The launcher is small, while manifest generation and process-signal behavior merit a standard reviewer.

**Files:**
- Create: `packaging/mcpb/bootstrap.cjs`
- Create: `packaging/mcpb/manifest.template.json`
- Create: `packaging/mcpb/package.json`
- Create: `packaging/mcpb/package-lock.json`
- Create: `src/main/java/dev/mcdevmcp/packaging/McpbManifestGenerator.java`
- Create: `src/test/java/dev/mcdevmcp/packaging/McpbManifestGeneratorTest.java`
- Create: `src/test/java/dev/mcdevmcp/packaging/McpbBundleIntegrationTest.java`
- Create: `src/test/resources/contracts/mcpb/manifest.json`
- Create: `scripts/build-mcpb.ps1`
- Modify: `build.gradle.kts`
- Modify: `manifest.json`

**Interfaces:**
- Consumes: exact shaded JAR, Java tool metadata/availability, MCPB v0.4-compatible template.
- Produces: generated `manifest.json`; `build/distributions/mcdev-mcp-3.0.0.mcpb`; launcher that validates Java 25 and delegates STDIO/signals/exit status only.

- [ ] **Step 1: Write failing manifest and extracted-bundle tests**

Tests assert all manifest tool names/schemas equal the Java catalog including `mc_record_video`; version equals `AppVersion.current()`; user configuration maps only supported runtime envs; bundled JAR SHA-256 equals the root shaded JAR; launcher has no MCP method/tool/resource strings; Java 24 is rejected; Java 25/26 version output is accepted; child exit status and termination are forwarded; extracted MCPB initializes and lists tools.

- [ ] **Step 2: Run packaging tests and observe missing generator/bundle**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.packaging.*" --console=plain`

Expected: FAIL at compilation or missing bundle.

- [ ] **Step 3: Implement generated MCPB metadata**

`McpbManifestGenerator` loads the template, injects Gradle/JAR version and the union tool metadata, and preserves user config for `MCDEV_SCRIPT_LOGS` plus existing supported settings. It never parses Java source. Gradle task `generateMcpbManifest` writes both root `manifest.json` and staging manifest, then a test proves `git diff --exit-code manifest.json` after generation.

The generated root `manifest.json` is Java-generated MCPB catalog/install metadata, not Node code, and contains no Node runtime selector, command, or server entry point. The separate staging manifest may name `packaging/mcpb/bootstrap.cjs` only where the MCPB packer schema requires it. `cutoverCheck` inspects both as metadata. All JavaScript, package metadata, npm dependencies, and npm execution stay under `packaging/mcpb/`; root `scripts/build-mcpb.ps1` is allowed only as PowerShell orchestration that sets the packaging directory explicitly before invoking those dependencies.

- [ ] **Step 4: Implement the packaging-only launcher**

`bootstrap.cjs` uses only Node built-ins. It resolves the sibling JAR, runs `java -version`, parses the first quoted/bare version token into a feature number, rejects below 25 on STDERR, then spawns `java -jar <jar> serve` with inherited stdin/stdout/stderr and environment. Forward `SIGINT`, `SIGTERM`, child errors, and child exit code/signal. It contains no JSON-RPC parsing and no tool/resource metadata.

- [ ] **Step 5: Build, extract, hash-check, and smoke-test MCPB**

`scripts/build-mcpb.ps1` accepts `-Jar`, verifies its checksum, stages that same file, runs the pinned `@anthropic-ai/mcpb` CLI from `packaging/mcpb`, extracts the result, hashes the inner JAR, and sends initialize/tools-list through the launcher. `MCDEV_MCP_SKIP_SMOKE=1` may skip only the final smoke in local build scripts; CI and release never set it.

Run:

```powershell
.\gradlew.bat clean test shadowJar generateMcpbManifest --console=plain
.\scripts\build-mcpb.ps1 -Jar build\libs\mcdev-mcp-3.0.0.jar
.\gradlew.bat test --tests "dev.mcdevmcp.packaging.*" --console=plain
```

Expected: tests PASS; MCPB contains exactly the launcher, manifest, and checksum-identical JAR plus MCPB metadata required by the packer.

- [ ] **Step 6: Commit MCPB packaging**

```powershell
git add packaging src/main/java/dev/mcdevmcp/packaging src/test/java/dev/mcdevmcp/packaging src/test/resources/contracts/mcpb scripts/build-mcpb.ps1 build.gradle.kts manifest.json
git commit -m "build: package Java server for MCPB"
```

## Task 15: Add Java 25/26 CI, Macrobenchmarks, And Release Provenance

**Recommended agent:** `gpt-5.6-terra`, high reasoning. Artifact provenance, cross-runtime test execution, benchmark statistics, workflow permissions, and release immutability are cross-cutting and expensive to repair after publication.

**Files:**
- Create: `benchmark/src/main/java/dev/mcdevmcp/benchmark/AnalysisBenchmarkMain.java`
- Create: `benchmark/src/main/java/dev/mcdevmcp/benchmark/BenchmarkResult.java`
- Create: `benchmark/src/main/java/dev/mcdevmcp/benchmark/BenchmarkDecision.java`
- Create: `benchmark/src/main/java/dev/mcdevmcp/benchmark/BenchmarkComparisonRun.java`
- Create: `benchmark/src/main/java/dev/mcdevmcp/benchmark/BenchmarkPolicy.java`
- Create: `benchmark/src/main/java/dev/mcdevmcp/benchmark/CorpusQualificationMain.java`
- Create: `src/test/java/dev/mcdevmcp/benchmark/BenchmarkPolicyTest.java`
- Create: `src/test/resources/contracts/indexer/corpus-probes.json`
- Create: `scripts/verify-release-assets.ps1`
- Create: `.github/workflows/benchmark.yml`
- Create: `.github/workflows/release.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/dependabot.yml`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: one Java-25-built JAR/test runtime bundle, complete immutable Minecraft 1.21.11 and 26.1 source/remapped-JAR inputs, Node-oracle comparison data, version property, MCPB artifact.
- Produces: correctness on Java 25 and 26; complete-corpus accounting and logical hashes; bounded-memory evidence; JSON benchmark artifacts; three-run preference evaluator; release JAR/checksum/MCPB assembled without rebuild.

- [ ] **Step 1: Write failing benchmark-policy and release-verifier tests**

Define the policy API:

```java
public record BenchmarkResult(
        int javaFeature, String vendor, String vmFlags,
        double indexClassesPerSecond, double callEdgesPerSecond,
        long indexPeakRssBytes, long callgraphPeakRssBytes) {}

public record BenchmarkDecision(
        boolean preferJava26, List<String> reasons) {}

public record BenchmarkComparisonRun(
        long workflowRunNumber, String machineId,
        BenchmarkResult java25, BenchmarkResult java26) {}

public final class BenchmarkPolicy {
    public static BenchmarkDecision evaluate(
            List<BenchmarkComparisonRun> threeRuns);
}
```

All four benchmark domain types are top-level. Keep workflow and machine IDs
as protocol identifiers, but use numeric metric types rather than formatting
measurements as strings inside the policy boundary.

Tests require exactly three consecutive runs; geometric mean of Java-26 index/callgraph throughput ratios >=1.05; each workflow ratio >=0.98; each Java-26 RSS ratio <=1.10. Any missing metric, mixed machine ID, nonconsecutive run, or failed threshold returns `preferJava26=false` with exact reasons.

- [ ] **Step 2: Run benchmark tests and observe missing implementation**

Run: `.\gradlew.bat test --tests "dev.mcdevmcp.benchmark.*" --console=plain`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement the same-runner benchmark executable**

Add a Gradle `benchmark` harness project compiled with release 25. `AnalysisBenchmarkMain` accepts immutable source root/remapped JAR and separate output root, runs one warmup then five measured index builds and callgraph builds, alternates Java-version order at workflow level, and writes one JSON record containing medians, class/edge throughput, peak RSS, GC, vendor/version, flags, input hashes, machine ID, and run ID. It never changes production caches.

`CorpusQualificationMain` uses the same process runner and accepts a typed
Minecraft version, immutable source/remapped-JAR inputs, Node baseline, probe
manifest, worker count, and separate output root. For complete 1.21.11 and 26.1
corpora it records every discovered Java unit, type-free package/module units,
logical table counts and ordered hash, probe signatures, diagnostics, peak live
heap, and peak RSS. It fails on any unaccounted/skipped unit, partial build,
probe mismatch, or `-Xmx4g` exhaustion. Count/signature differences from the
Node oracle require an explicit reviewed explanation; legacy inaccuracy is not
made normative.

- [ ] **Step 4: Make CI build once and execute the same bits on 25 and 26**

`ci.yml` jobs:

1. `build-java25`: checkout, install Temurin 25, run `clean test shadowJar runtimeTestBundle`, hash JAR, upload JAR/checksum/compiled test runtime.
2. `runtime-matrix`: matrix Temurin 25 and 26, download exact artifacts, verify hash, run compiled integration tests and direct JAR STDIO/H2/TinyRemapper/Vineflower smoke without compilation.
3. `mcpb`: download exact JAR/checksum, generate metadata, pack/extract/smoke, upload MCPB.
4. `conformance`: install Temurin 25 and Node 24 with `actions/setup-node`, run the test-only Java harness, and invoke only the pinned build-time `@modelcontextprotocol/conformance@0.1.16` package through `npx`.

No matrix job invokes `shadowJar`.

The exact-JAR smoke also loads every merged service provider, opens H2,
and scans the archive to prove stale `.SF`, `.RSA`, and `.DSA` signatures are
absent; class loading must produce no signed-archive verification failure.

- [ ] **Step 5: Add scheduled/manual Java 25 versus 26 macrobenchmark**

`benchmark.yml` runs weekly and by dispatch on one runner. It downloads/prepares the complete 1.21.11 and 26.1 inputs once and verifies their hashes. Before timing, it runs `CorpusQualificationMain` under Java 25 and 26 with one and up to four workers; all four runs for each corpus must have identical ordered logical hashes and complete unit accounting under `-Xmx4g`. It materializes the untouched Node oracle only in ignored scratch, captures baseline counts and representative signatures, and requires reviewed explanations for intentional Java corrections.

The workflow then runs both JDKs with default G1 in counterbalanced order, uploads raw JSON and corpus reports, and uses GitHub API with least-privilege `actions: read` to download the preceding two successful same-machine comparison artifacts. `BenchmarkPolicy` evaluates all three. A Parallel-GC job is manual/advisory and never changes the decision. Workflow summaries state either threshold pass or no preference; they do not edit README automatically.

Keep daily Dependabot version updates for Gradle and GitHub Actions. Dependency
coordinates remain exact and reviewable; dynamic selectors are rejected. The
CI dependency policy fails when an unused catalog alias remains or when a
test-only HTTP container appears in production `runtimeClasspath` or the
shaded JAR.

- [ ] **Step 6: Build release assets once and verify provenance**

`release.yml` triggers on `v*`, checks tag equals `gradle.properties` version, invokes/reuses the Java-25 build, tests exact artifacts on 25/26, builds MCPB from the downloaded JAR, and calls `scripts/verify-release-assets.ps1` before creating a GitHub Release. The verifier requires only:

```text
mcdev-mcp-<version>.jar
mcdev-mcp-<version>.jar.sha256
mcdev-mcp-<version>.mcpb
```

It verifies SHA-256 text, inner MCPB JAR hash, JAR `Implementation-Version`, MCPB version, and filenames. Use release permissions `contents: write` only in the final release job.

- [ ] **Step 7: Validate workflows and Gradle tasks locally**

Run:

```powershell
.\gradlew.bat clean test shadowJar runtimeTestBundle benchmarkClasses --console=plain
.\scripts\verify-release-assets.ps1 -DryRun -Version 3.0.0 -Directory build\distributions
```

Expected: Gradle PASS; dry-run verifier either PASS with locally built three assets or names the exact missing asset without publishing.

- [ ] **Step 8: Commit CI, benchmark, and release workflows**

```powershell
git add benchmark scripts/verify-release-assets.ps1 .github build.gradle.kts settings.gradle.kts
git commit -m "ci: test and release Java artifacts"
```

## Task 16: Audit The Early Cutover And Final Documentation

**Recommended agent:** `gpt-5.6-terra`, high reasoning. The implementation was removed during the early worktree cutover; this task verifies that invariant after parity and packaging work without recreating or deleting the oracle evidence.

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/MULTIVER.md`
- Modify: `docs/VF.md`
- Modify: `docs/fork.md`
- Modify: `skills/minecraft-dev-loop/SKILL.md`
- Modify: `.gitignore`
- Modify: `build.gradle.kts`
- Audit: `manifest.json` (Java-generated metadata only)
- Audit: `packaging/mcpb/**`
- Audit: `scripts/build-mcpb.ps1`

**Interfaces:**
- Consumes: passing parity, conformance, packed-artifact, Java 25/26, static/runtime, indexer/callgraph tests.
- Produces: final evidence that the early cutover still holds; only `packaging/mcpb/bootstrap.cjs`, packaging-local package metadata, and packaging-local npm dependencies remain Node-related; root generated JSON remains free of Node server entry points; transition/deprecation documentation.

- [ ] **Step 1: Add a failing repository-cutover invariant test**

Extend the existing Gradle `cutoverCheck` with focused bypass tests before changing its matcher. It scans tracked files and relevant build/package/manifest metadata, allowing only `packaging/mcpb/bootstrap.cjs`, packaging-local `package.json`/`package-lock.json`, and frozen JSON/text fixtures. It rejects `.js`, `.mjs`, `.cjs`, `.ts`, `.tsx`, `java-worker`, root or non-packaging package metadata, Jest, ESLint, TypeScript, Bun, Gson/direct `JsonNode`, `java-parser`, `sql.js`, Node MCP SDK, every retired parser/worker environment name, java-callgraph2, package-JSON production indexes, downloaded analysis-tool launchers, and Node server entry points in non-packaging JSON metadata. Packaging manifests may point only to `packaging/mcpb/bootstrap.cjs`; root generated JSON must contain no Node runtime selector, command, or server entry point. Wire the invariant into Gradle `check`.

Run: `.\gradlew.bat cutoverCheck --console=plain`

Expected for each synthetic bypass: FAIL and list the forbidden tracked path or reference. Remove all synthetic index/worktree entries before continuing; the real repository then passes.

- [ ] **Step 2: Run the complete post-parity audit gate**

Run:

```powershell
.\gradlew.bat clean test parityTest shadowJar --console=plain
.\scripts\run-conformance.ps1
.\scripts\build-mcpb.ps1 -Jar build\libs\mcdev-mcp-3.0.0.jar
```

Expected: Java parity, conformance, and packed-artifact gates PASS. Differential tests materialize the pinned Node oracle through `NodeOracleMaterializer`; no command runs from deleted root npm metadata.

- [ ] **Step 3: Audit the retired implementation and environment inventory**

Confirm the already-removed TypeScript/Jest/ESLint toolchain, nested Java worker/protocol, legacy scripts, and root npm metadata have not returned. Keep frozen contract/oracle fixtures and `contracts/node-oracle.json`. Verify `NodeOracleMaterializer` owns its Java `ProcessBuilder` workflow from Task 13 and that all Node oracle build/test activity remains in ignored scratch.

Confirm production support remains absent for:

```text
MCDEV_INDEXER
MCDEV_AST_PARSER
MCDEV_SUPPRESS_INDEXER_HINT
MCDEV_JAVA_WORKER_COMMAND
MCDEV_JAVA_WORKER_ARGS_JSON
MCDEV_INDEX_WORKERS
MCDEV_INDEX_BATCH_SIZE
MCDEV_INDEX_WORKER_HEAP_MB
MCDEV_INDEX_WORKER_RETRY_HEAP_MB
MCDEV_INDEX_PARSE_WORKER_PATH
MCDEV_INDEX_WORKER_MARKER
MCDEV_INDEX_SINGLE_FILE_FALLBACK
MCDEV_MCP_REMAPPER_HEAP
MCDEV_ARGV_CAPTURE
```

Preserve `DEBUGBRIDGE_PORT`, `MCDEV_RUN_COMMAND`, `MCDEV_SCRIPT_LOGS`, `MCDEV_MCP_DEBUG_LOG`, build-only `MCDEV_MCP_SKIP_SMOKE`, and new `MCDEV_INDEX_THREADS`.

- [ ] **Step 4: Rewrite user and architecture documentation**

Document Java 25 minimum, `java -jar ...` CLI/client configuration, one-JAR architecture, embedded tools, H2 rebuild migration, ignored legacy JSON until `clean --index`, new/retired environment variables, GitHub Release/MCPB installation, DebugBridge v2.0.0 compatibility, and npm package retirement. Do not claim Java 26 preferred unless three benchmark artifacts satisfy `BenchmarkPolicy`; otherwise state both runtimes are supported and benchmark evidence is pending/neutral.

Add transition text that npm `mcdev-mcp` 2.2.1 is the final Node line and the next Java release is installed from GitHub Releases or MCPB. The release workflow must not execute `npm deprecate`; that external action remains a separately approved release operation.

Keep `manifest.json` at the root as Java-generated metadata with no Node server entry point. Keep `scripts/build-mcpb.ps1` as non-Node orchestration; it must set `packaging/mcpb/` as the working directory for every npm command, and no npm dependency or JavaScript execution may escape that packaging subtree.

- [ ] **Step 5: Run cutover, clean-build, forbidden-reference, and packed-artifact checks**

Run:

```powershell
.\gradlew.bat clean check cutoverCheck shadowJar generateMcpbManifest --console=plain
.\scripts\build-mcpb.ps1 -Jar build\libs\mcdev-mcp-3.0.0.jar
git ls-files | rg "(?i)(\\.tsx?$|\\.[mc]?js$|^java-worker/|(^|/)package(-lock)?\\.json$|tsconfig|jest|eslint)"
git grep -n -I -E "typescript|ts-jest|@modelcontextprotocol/sdk|java-parser|sql\\.js|java-callgraph2|MCDEV_AST_PARSER|MCDEV_INDEXER" -- ":(exclude)docs/superpowers/**" ":(exclude)src/test/resources/contracts/**" ":(exclude)src/test/resources/oracle/**" ":(exclude)contracts/node-oracle.json"
git -C C:\Users\ttski\Projects\mcdev-mcp status --short --branch
```

Expected: Gradle/MCPB PASS; the tracked-file search returns only explicitly permitted MCPB paths; the reference audit has no production/build matches; original master remains clean.

- [ ] **Step 6: Commit the final audit**

```powershell
git add -A
git commit -m "docs: audit the early Java cutover"
```

## Task 17: Run The Full Acceptance Audit And Prepare The Branch

**Recommended agents:** controller performs verification; `gpt-5.6-sol`, high reasoning performs the final whole-branch review. This is the one review where maximum breadth is worth the usage; use no second broad reviewer unless the first reports an evidence gap.

**Files:**
- Create: `docs/verification/2026-07-10-pure-java-acceptance.md`
- Modify production/test files only when verification exposes a concrete gap.
- Update: `.superpowers/sdd/progress.md` (ignored execution ledger, not committed).

**Interfaces:**
- Consumes: the complete branch and approved design acceptance criteria.
- Produces: requirement-by-requirement evidence, clean independent review, pushed `codex/java25-indexer-callgraph` branch. No merge/release/publication.

- [ ] **Step 1: Build an acceptance evidence matrix from the approved design**

Record each requirement and its authoritative command/artifact: production STDIO, full tool/resource catalog, CLI, environment inventory, Javac-only parser, parse-failure atomicity, complete Minecraft 1.21.11/26.1 corpus accounting and memory bounds, Class-File callgraph, 5000 refs, no java-callgraph2, DebugBridge fixtures/live smoke, Java 25/26, benchmark outputs/policy, shaded services/native resources, exact JAR checksum in MCPB, release dry run, automatic dependency-update policy, no unused or production-scoped test container, no TypeScript runtime, and original master cleanliness. Mark missing evidence as incomplete, not assumed.

- [ ] **Step 2: Run the complete local verification from a clean build directory**

Run:

```powershell
.\gradlew.bat clean check cutoverCheck shadowJar runtimeTestBundle benchmarkClasses generateMcpbManifest --console=plain
.\scripts\run-conformance.ps1
.\scripts\build-mcpb.ps1 -Jar build\libs\mcdev-mcp-3.0.0.jar
.\scripts\verify-release-assets.ps1 -DryRun -Version 3.0.0 -Directory build\distributions
git diff --check
git status --short --branch
git -C C:\Users\ttski\Projects\mcdev-mcp status --short --branch
```

Expected: all build/test/package/conformance checks PASS; design branch clean except ignored evidence; original prints only clean master status.

Then run IntelliJ MCP `build_project` with a full rebuild and
`get_file_problems` with warnings enabled for every production and test Java
file. Expected: successful build, no timeouts, and zero actionable errors or
warnings. Inspection must not trigger or justify reformatting unrelated code.

- [ ] **Step 3: Run Java 25 and Java 26 runtime acceptance on the exact JAR**

Use CI or local installed JDKs to run the uploaded `runtimeTestBundle` and exact SHA-checked JAR under both versions. Capture feature/vendor, test counts, failures, JAR SHA, H2 smoke, Tiny Remapper/Vineflower fixture, STDIO initialize, and MCPB launch. A test rebuilt under the second JDK is not acceptable evidence for exact-artifact runtime parity.

Run the latest successful or manually dispatched full-corpus qualification on
the complete, hash-verified Minecraft 1.21.11 and 26.1 inputs. Require Java 25
and 26, one and up to four workers, identical ordered logical database hashes,
complete compilation-unit accounting, no fallback/skips/partial rows, all
representative probes, and success under `-Xmx4g`. Record peak live heap/RSS
and every reviewed Node-baseline delta. Missing corpus artifacts or an
unexplained delta leaves acceptance incomplete.

- [ ] **Step 4: Run a live DebugBridge v2.0.0 smoke test**

Against a user-launched compatible Minecraft instance, run non-mutating `mc_connect`, snapshot, entity/block query, screen inspect, screenshot or texture, and wait-state checks; run session-control, command, or video only when their bridge/server gates are explicitly enabled. Record mod release/commit, Minecraft version, port, tool names, and pass/fail without retaining game paths or payload secrets. If no live instance is available, this acceptance item remains incomplete and the branch is not represented as release-ready.

- [ ] **Step 5: Commit the sanitized acceptance evidence**

Write the matrix, exact commands, artifact hashes, Java runtime identities, test counts, conformance result, full-corpus unit/count/hash/probe and memory reports, reviewed Node-baseline deltas, MCPB inner-JAR comparison, dependency-update/isolation evidence, benchmark-policy state, live-smoke state, and known residual risks to `docs/verification/2026-07-10-pure-java-acceptance.md`. Exclude machine-specific game paths, secrets, raw bridge payloads, and usernames.

```powershell
git add docs/verification/2026-07-10-pure-java-acceptance.md
git commit -m "test: record Java rewrite acceptance"
```

- [ ] **Step 6: Dispatch one final whole-branch reviewer**

Generate the review package from `git merge-base master HEAD` through `HEAD`. Give the reviewer the approved spec, this plan, acceptance evidence matrix, task ledger, and full review package paths. Require findings ordered by severity, specification compliance verdict, implementation-quality verdict, and remaining test risk. Fix all Critical/Important findings in one fix wave, rerun covering tests, regenerate the package, and re-review until clean.

- [ ] **Step 7: Verify history, stash, branch, and remote target before push**

Run:

```powershell
git log --oneline --decorate --reverse master..HEAD
git stash list
git branch --show-current
git status --short --branch
git remote -v
```

Expected: logical task commits; preserved Bun/TS experiment stash still present; branch is `codex/java25-indexer-callgraph`; worktree clean; push target is the codex branch, never master.

- [ ] **Step 8: Push the reviewed branch without publishing a release**

Run: `git push --force-with-lease origin codex/java25-indexer-callgraph`

Expected: remote codex branch updates successfully. Do not merge, tag, create a release, deprecate npm, or delete the isolated worktree.

## Specification Coverage Map

| Approved design area                                                                                                                               | Implemented and proven by              |
|----------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|
| Immutable Node parity oracle and `mc_record_video` catalog correction                                                                              | Tasks 1 and 13                         |
| Root Java 25 build, one shaded JAR, version authority, signature/service/native-resource handling                                                  | Tasks 2, 14, 15, and 17                |
| Java 25 instance entry point, top-level production types, semantic Java values, preserved IntelliJ style, and warning-clean Gradle/IDE diagnostics | Tasks 2 through 17, audited in Task 17 |
| SDK-only JSON mapping, generic typed tool bindings, explicit wire/domain conversion, and Gson absence                                              | Tasks 3, 9 through 13, 16, and 17      |
| Official MCP protocol via the Java SDK STDIO server, tools/resources, errors, STDOUT hygiene                                                       | Tasks 3 and 13                         |
| Frozen STDIO-only production transport, with Streamable HTTP and any container confined to the test conformance harness                            | Tasks 3, 13, 15, and 17                |
| CLI and cache lifecycle                                                                                                                            | Tasks 4 and 8                          |
| Javac-only accurate source indexing, bounded shared-corpus memory, semantic type identity, and complete 1.21.11/26.1 qualification                 | Tasks 5, 15, and 17                    |
| Normalized H2 symbols, locks, atomic promotion, legacy detection/cleaning                                                                          | Tasks 4, 5, and 6                      |
| Static tools and intentional user search regex                                                                                                     | Task 6                                 |
| Class-File API callgraph and full `mc_find_refs` behavior                                                                                          | Task 7                                 |
| Embedded download, Tiny Remapper, and Vineflower pipeline                                                                                          | Task 8                                 |
| Versioned DebugBridge envelopes, fixtures, session, and every runtime tool                                                                         | Tasks 9 through 12                     |
| Production STDIO integration plus official URL-based conformance 0.1.16 harness                                                                    | Task 13                                |
| MCPB minimal Node launcher containing the exact release JAR                                                                                        | Task 14                                |
| Java 25/26 correctness, same-runner benchmark policy, reproducible dependencies with daily update PRs, and build-once release provenance           | Task 15                                |
| Environment preservation/removal inventory, TypeScript/worker deletion, docs transition                                                            | Task 16                                |
| Live DebugBridge smoke, full acceptance evidence, independent whole-branch review, guarded push                                                    | Task 17                                |
