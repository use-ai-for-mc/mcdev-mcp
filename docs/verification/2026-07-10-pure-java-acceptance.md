# Pure-Java MCP Server Acceptance Audit

Audit date: 2026-08-13

Continuation verification: 2026-08-20

Module completion verification: 2026-08-23

Design: `docs/superpowers/specs/2026-07-10-pure-java-mcp-server-design.md`

Execution plan: `docs/superpowers/plans/2026-07-10-pure-java-mcp-server.md`
Audited branch snapshot: `codex/java25-indexer-callgraph` at
`64d5149bf7f054a2b98656d2ffbb52607dc17117`

Continuation base snapshot: `codex/java25-indexer-callgraph` at
`077bfe7e1b11a0045cebfe834e28ad750e2962e7`, plus the uncommitted
benchmark/conformance Gradle-project split described below.

## 2026-08-20 Continuation Addendum

The interrupted Windows handoff and the later module-creation handoff were
reconciled in the active Linux checkout. Benchmark sources, tests, and corpus
probe resources now live in the independently buildable `:benchmark` project;
the HTTP conformance harness lives in `:conformance`; and both consume the root
application without becoming production dependencies. The root remains the
only release application and continues to compile and publish Java 25 bytecode.

The continuation verification passed:

```text
./gradlew projects
./gradlew :benchmark:test :conformance:conformanceHarnessJar test
./gradlew clean test check shadowJar runtimeTestBundle benchmarkBundle benchmarkClasses generateMcpbManifest conformanceHarnessJar -x releaseVerifierTest
./gradlew test :benchmark:test -PtestJavaVersion=26 --rerun-tasks
./gradlew parityTest --no-configuration-cache
npx --yes @modelcontextprotocol/conformance@0.1.16 server --url http://127.0.0.1:3000/mcp --suite active
actionlint .github/workflows/ci.yml .github/workflows/benchmark.yml .github/workflows/release.yml
```

The parity run compared the current Java server against the clean frozen
`master` oracle at `7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6` and passed. The
official active conformance suite reported 40 passed and zero failed. The
SHA-verified build-once JAR passed `RuntimeArtifactSmokeMain` under OpenJDK 25
and OpenJDK 26, and both harness JARs expose stable automatic module names.
IntelliJ reformat, changed-file inspection, and full project rebuild also
completed with zero reported problems.

The continuation artifact SHA-256 is
`1e2c62025b316239bff46f9b9d19fd445323d29da931629afede583e75f9330c`.
The Windows-only `releaseVerifierTest`/MCPB PowerShell lanes were not repeated
locally because PowerShell is unavailable; the published base snapshot's
GitHub CI run `31752839386` is green, but it predates this uncommitted module
split. A dedicated frozen-Node parity job is now present in the working-tree CI
workflow and has passed locally; it has not yet run remotely.

This addendum does not change the release-readiness verdict. Full real-corpus
qualification, live DebugBridge smoke evidence, the scheduled benchmark
history, independent review of the continuation diff, and an authorized
reviewed commit/push remain outstanding.

## 2026-08-23 JPMS Completion Addendum

`mcp-tool-api` now contains an explicit `module-info.java`. Its build-scoped
Gradlex transform replaces the invalid names published by the reviewed MCP SDK
snapshot and supplies complete core and Jackson 3 descriptors, including the
JSON service `uses` and provider metadata. The module-path smoke compiles and
runs as a separate named consumer, decodes a record, and resolves both default
JSON services without `ALL-MODULE-PATH`, `--add-reads`, or `--add-exports`.

The release architecture remains intentionally dual-purpose: Gradle and JPMS
boundaries govern compilation and verification, while the root shaded JAR is
the classpath deployment artifact required by direct `java -jar` and MCPB. The
shaded JAR excludes dependency module descriptors.

The completion verification passed:

```text
./gradlew :mcp-tool-api:check
./gradlew :mcp-tool-api:check -PtestJavaVersion=26 --rerun-tasks
./gradlew clean test check shadowJar runtimeTestBundle benchmarkBundle benchmarkClasses generateMcpbManifest conformanceHarnessJar -x releaseVerifierTest
./gradlew test :benchmark:test :mcp-tool-api:check -PtestJavaVersion=26 --rerun-tasks
./gradlew parityTest --no-configuration-cache
```

`jar --describe-module` reports the explicit binding descriptor and its
transitive MCP core requirement. The shaded JAR contains no `module-info.class`
entry and passed `RuntimeArtifactSmokeMain` on OpenJDK 25 and OpenJDK 26. Its
SHA-256 remains
`1e2c62025b316239bff46f9b9d19fd445323d29da931629afede583e75f9330c`,
demonstrating that the JPMS build boundary did not alter the reviewed release
bytes. The frozen Node parity suite also passed after materializing the clean
`master` oracle worktree at `7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6`.

## 2026-08-24 Typed Tool API Addendum

The former one-interface `mcp-tool-binding` scope is now `mcp-tool-api` and owns
the reusable input and output contracts. `JsonType<T>` carries a runtime Java
`Type`, backed by either `Class<T>` or SDK `TypeRef<T>`; `TypedJson<T>` keeps
that target beside the raw JSON value and performs conversion only through
`McpJsonMapper`. This preserves generic targets such as
`List<InventoryItem>` without adding Jackson APIs or embedding Java class names
in protocol JSON.

`StructuredToolResult<T>` carries its `JsonType<T>`, typed Java value, explicit
fallback MCP content, and error state. The root SDK adapter passes only the
typed value to `CallToolResult.structuredContent`; existing content-only tools
retain their exact output. Module tests cover raw and generic Java targets, the
adapter test proves the Java object reaches the SDK boundary unchanged, and
the named-module smoke covers decoding, structured-result serialization, and
SDK service loading.

## 2026-08-24 Minecraft 1.21.11 Analysis Debug Addendum

An ignored project-local cache was initialized from the real Mojang 1.21.11
metadata under Java 25. Remapping verified and published a 29,231-entry JAR and
Vineflower published 6,622 Java files. The first index attempt exposed a
missing compile-only `javax.annotation.meta.TypeQualifierDefault`; the compiler
classpath shim now synthesizes that annotation with its required
`ElementType[] value()` method. The next attempt exposed Vineflower's
weaker-access reconstruction of `MinecraftServer.doRunTask(TickTask)`; the
indexer now retains the narrow `compiler.err.override.weaker.access` diagnostic
without treating otherwise valid bytecode declarations as a fatal index error.
Unresolved stored field, method, and hierarchy types remain fatal.

The completed corpus accounts for all units exactly: 6,622 discovered and
parsed units equal 6,107 typed units plus 515 package-only units. The symbol
database contains 511 packages, 6,107 types, 36,304 fields, 45,975 methods, and
67,070 parameters. Java 25 and Java 26 rebuilds produced the same logical
symbol hash,
`620af66ff150db1033a207705c939745b552cc2c052aa3c9e3d849db40210d9b`.
Both runtimes also produced the same callgraph generation
`8a93b2745d6a10095b6d5a16dadac7f5771247222bbfa7b375db2615e8a357f2`
with 10,291 classes, 89,557 methods, 387,125 edges, and identical artifact
hashes.

A real shaded-JAR STDIO session successfully exercised `mc_version`,
`mc_search`, `mc_get_class`, `mc_get_method`, `mc_list_packages`,
`mc_list_classes`, `mc_find_hierarchy`, and both directions of `mc_find_refs`
against the published index/callgraph. Interactive progress is now bounded by
percentage transitions, index progress shows at most 20 compact diagnostics
while retaining all 842 in evidence, and the Mojang resolver excludes 52
explicit `natives-*` classifier artifacts from compiler-library downloads.
Wrapped analysis failures now include their immediate cause in CLI error text.
The resulting shaded JAR SHA-256 is
`402f12bf6129b060a1c716b941ac5902e23ed9a67fa07d80fb03213c467d10cb`;
unlike the descriptor-only JPMS amendment, the API relocation and analysis
hardening intentionally change application bytecode.
Two consecutive forced `shadowJar` builds produced that same hash after the
task was made timestamp-free and reproducibly ordered.

This is strong exploratory evidence for 1.21.11, not the formal corpus gate:
the reviewed Node baseline/expectation artifacts, required one-versus-four
worker reports, 26.1 corpus, memory limits, and scheduled history remain
outstanding.

## Verdict

**INCOMPLETE - do not represent this branch as release-ready.**

The repository-owned unit, integration, parity, packaging, dependency, and
runtime-artifact checks provide substantial evidence for the pure-Java
implementation. Two mandatory end-to-end gates have no qualifying evidence in
this audit:

1. Complete, hash-verified Minecraft 1.21.11 and 26.1 corpus qualification on
   Java 25 and Java 26, with one and up to four workers and the required memory,
   accounting, logical-hash, probe, and reviewed Node-delta reports.
2. A live, non-mutating DebugBridge v2.0.0 smoke test against a user-launched
   compatible Minecraft instance.

The three-consecutive-run benchmark history required for any Java 26
performance preference is also unavailable. Java 25 and Java 26 therefore
remain supported peers; this audit makes no preferred-runtime claim.

The final IntelliJ rebuild completed without a visible build error. The final
whole-project inspection reported zero Java errors and zero Java file
diagnostics. Its only Java-category
findings were three accepted project-model warnings caused by the intentional
`mcp-tool-api.main` dependency in the synthetic benchmark, conformance, and
runtime-test modules, where production runtime-classpath parity is required.

`PASS` below means the cited repository-owned check or inspected artifact
directly proves the stated requirement. `INCOMPLETE` means required external or
current-snapshot evidence was unavailable; implementation or workflow presence
alone is not treated as acceptance.

## Requirement Matrix

| Requirement | Status | Evidence |
|---|---|---|
| Production STDIO server and official conformance 0.1.16 | PASS | `McpStdioIntegrationTest`, `DifferentialMcpTest`, and the exact-JAR runtime harness cover initialize, notifications, tools/resources, calls, errors, STDOUT hygiene, and shutdown. The current `scripts/run-conformance.ps1` run passed the official `@modelcontextprotocol/conformance@0.1.16` active server suite against the test-only HTTP harness. |
| Complete compatible tool and resource catalog | PASS | The frozen contract contains 31 default tools, 33 tools with the two opt-in development tools (`mc_script_logs`, `mc_run_command`), and two resources (`mcdev://guides/python-scripting`, `mcdev://guides/dev-loop`). `ToolCatalogContractTest`, `ResourceCatalogTest`, `HandlerCompletenessTest`, `DifferentialMcpTest`, and exact-JAR `tools/list` comparison cover schemas, descriptions, handlers, and resources. |
| CLI surface and documented switches | PASS | `CliContractTest`, `DifferentialCliTest`, `AnalysisPipelineIntegrationTest`, and exact-JAR runtime smoke cover `serve`, `init`, `callgraph`, `rebuild`, `status`, and `clean`, including version validation, progress/error behavior, and cache lifecycle. |
| Environment preservation/removal inventory | PASS | `README.md` and `docs/ARCHITECTURE.md` preserve `DEBUGBRIDGE_PORT`, `MCDEV_RUN_COMMAND`, `MCDEV_SCRIPT_LOGS`, and `MCDEV_MCP_DEBUG_LOG`; document `MCDEV_INDEX_THREADS`; retain `MCDEV_MCP_SKIP_SMOKE` only for packaging; and remove `MCDEV_INDEXER`, `MCDEV_AST_PARSER`, `MCDEV_SUPPRESS_INDEXER_HINT`, `MCDEV_JAVA_WORKER_COMMAND`, `MCDEV_JAVA_WORKER_ARGS_JSON`, `MCDEV_INDEX_WORKERS`, `MCDEV_INDEX_BATCH_SIZE`, `MCDEV_INDEX_WORKER_HEAP_MB`, `MCDEV_INDEX_WORKER_RETRY_HEAP_MB`, `MCDEV_INDEX_PARSE_WORKER_PATH`, `MCDEV_INDEX_WORKER_MARKER`, `MCDEV_INDEX_SINGLE_FILE_FALLBACK`, `MCDEV_MCP_REMAPPER_HEAP`, and test-only `MCDEV_ARGV_CAPTURE`. `cutoverCheck` and `cutoverCheckBypassTest` reject tracked regressions and tested bypass spellings. |
| Javac-only production source parsing | PASS | Production uses `JavaCompiler`, `StandardJavaFileManager`, `JavacTask`, compiler trees, and in-memory source objects. `JavacSourceParserTest`, `SourceIndexerIntegrationTest`, `TypeResolutionTest`, and `cutoverCheck` cover language constructs, attribution, source accounting, and the absence of regex, TypeScript-AST, and fallback parsers. |
| Parse and index failures preserve the prior database | PASS | `IndexerFailureAtomicityTest` proves syntax error, malformed UTF-8, duplicate binary name, unresolved identity, cancellation, and required-module/package failures leave the prior published H2 database unchanged. H2 promotion/restore and validation are additionally covered by `AtomicH2DatabaseTest`, `SymbolIndexWriterValidationTest`, and `DatabaseLockProcessTest`. |
| Complete Minecraft 1.21.11 and 26.1 corpus qualification | **INCOMPLETE** | No complete immutable corpus manifest/artifact was available. Therefore there is no accepted final report for Java 25/26, worker counts 1 and up to 4, complete compilation-unit accounting, no skips/fallback/partial rows, identical ordered logical hashes, representative probes, `-Xmx4g`, peak live heap/RSS, or reviewed Node-baseline deltas. Synthetic and harness tests do not substitute for this gate. |
| Class-File API callgraph and complete `mc_find_refs` behavior | PASS | `InvocationExtractorTest` and `CallgraphScannerIntegrationTest` exercise `java.lang.classfile`, all required invoke opcodes, supported `invokedynamic` method handles, descriptors, call-site multiplicity, line numbers, deterministic worker behavior, cancellation, and atomic publication. `CallgraphRepositoryTest`, `McFindRefsContractTest`, and `StaticToolContractTest` prove callers/callees, overload aggregation, stable ordering, default 100, exact 5000, 5001 truncation, and hard capping. |
| No java-callgraph2 production/build/parser surface | PASS | `cutoverCheck` rejects the retired repository clone/build/report/parser surface. The tracked-source audit found no production java-callgraph2 implementation or dependency. Historical names in the approved migration documents are evidence text, not runtime code. |
| DebugBridge v2 fixtures, envelopes, sessions, and runtime tools | PASS | `BridgeJsonContractTest`, `BridgeClientTest`, `BridgeSessionTest`, `BridgeLifecycleTest`, `BridgeProbeTest`, `CoreRuntimeToolContractTest`, `MediaRuntimeToolContractTest`, `SessionRuntimeToolContractTest`, and scripted parity fixtures cover versioned wire envelopes, capability gates, reconnect/cancellation, binary media, and every registered runtime handler without depending on the mod at compile time. |
| Live DebugBridge v2.0.0 smoke | **INCOMPLETE** | No compatible user-launched Minecraft/DebugBridge instance was available. `mc_connect`, snapshot, entity/block query, screen inspection, screenshot or texture, and wait-state were not exercised live. No game path, raw bridge payload, port, or secret is retained. Mutating session/command/video operations were not attempted. |
| Java 25 compilation and Java 25/26 correctness | PASS | All Java source sets compile with `--release 25`, `-Xlint:all`, and `-Werror`. Recorded full controller matrices passed on Temurin 25.0.3+9-LTS and Temurin 26.0.1+8. The build-once runtime harness runs the exact SHA-checked shaded JAR without recompilation and covers manifest/services, H2, Tiny Remapper, Vineflower decompile-and-compile, CLI, STDIO initialize, and `tools/list`. See the run ledger below for the current audit artifact. |
| Benchmark output and preferred-runtime policy | **INCOMPLETE** | `AnalysisBenchmarkMainTest`, `BenchmarkPolicyTest`, and `CorpusQualificationMainTest` prove one warmup plus five measurements, medians, throughput, GC/runtime/JVM flags, RSS, corpus provenance, and the exact three-run policy (geometric mean at least 1.05, each workflow at least 0.98, RSS at most 1.10). `.github/workflows/benchmark.yml` publishes machine-readable artifacts and separates scheduled policy from advisory manual runs. No accepted three-consecutive-scheduled-run history or full external corpus benchmark artifact was available, so no Java 26 preference is authorized. |
| Shaded JAR services, signatures, and embedded resources | PASS | `ShadedJarSmokeTest` and `RuntimeArtifactSmokeMain` cover merged service descriptors, H2 `ServiceLoader` and `DriverManager`, H2 read/write/close under `--illegal-native-access=deny`, Tiny Remapper resource preservation, Vineflower output and recompilation, and signed-archive failure prevention. Direct archive inspection found 6,216 entries, one `META-INF/services/java.sql.Driver`, zero stale `.SF`/`.RSA`/`.DSA`, zero Gson entries, zero Tomcat/container entries, and zero TypeScript entries. |
| MCPB contains the exact release JAR and only the minimal launcher surface | PASS | `McpbLauncherTest`, `McpbManifestGeneratorTest`, `McpbBundleIntegrationTest`, `scripts/build-mcpb.ps1`, and the extracted-bundle smoke verify Java 25 preflight, literal `java -jar ... serve`, optional-placeholder removal, signal/exit forwarding, exact staged/extracted JAR SHA-256, server identity, and tool names. The packed archive is constrained to `bootstrap.cjs`, `manifest.json`, and the shaded JAR. Node and npm are packaging-only. |
| Release asset set and dry-run provenance | PASS | `releaseVerifierTest` exercises positive and negative provenance fixtures. `scripts/verify-release-assets.ps1 -DryRun` accepts exactly `mcdev-mcp-3.0.0.jar`, `.jar.sha256`, and `.mcpb`, checks versioned names, manifest version, checksum, and inner-JAR identity. `.github/workflows/release.yml` builds once on Java 25, tests the downloaded artifact on Java 25/26, builds MCPB from that artifact, and publishes only after read-only verification. No tag, release, or publication was performed by this audit. |
| Dependency updates, exact coordinates, and test-container isolation | PASS | `.github/dependabot.yml` schedules daily Gradle and GitHub Actions updates. `dependencyPolicyCheck`, `mcpSdkSnapshotCheck`, and `ConformanceDependencyIsolationTest` reject dynamic/ranged selectors, unused or mismatched SDK components, Gson, and production-scoped conformance containers. The only HTTP container dependency is Tomcat in the test-only `:conformance` project; direct shaded-JAR inspection found zero Tomcat entries. GitHub Actions are pinned by commit SHA. |
| Single version authority and one executable JAR | PASS | `gradle.properties` defines `3.0.0`; `AppVersionTest`, generated manifest tests, packaging scripts, and release verifier prove propagation to CLI/MCP metadata, `Implementation-Version`, filenames, checksum, and MCPB metadata. The shaded manifest names `dev.mcdevmcp.app.Main`; there is no second production server artifact. |
| SDK `McpJsonMapper` is the sole application JSON API | PASS | `SdkJsonMapperTest`, `ToolBindingTest`, `GsonAbsenceTest`, `mcpSdkSnapshotCheck`, and archive inspection cover typed whole-map binding, explicit wire/domain conversion, Jackson/Gson source policy, runtime graph constraints, and zero `com/google/gson/` classes in the shaded JAR. Application source does not directly import Jackson implementation, annotations, or `JsonNode`. |
| No TypeScript/Bun/Kotlin/Jest/ESLint/runtime npm/worker protocol remains | PASS | `cutoverCheck` and its synthetic bypass tests reject retired tracked implementation surfaces, legacy package-index readers/writers, and worker JSON protocols. Tracked code has no TypeScript or Kotlin application source; `package.json` and its lockfile exist only under `packaging/mcpb` for the pinned MCPB packer. The shaded JAR contains zero TypeScript entries. Legacy JSON cache data is ignored except for explicit `clean --index`. |
| Java structure, semantic values, formatting, and diagnostics | PASS | `JavaSourceLayoutTest` enforces package/path, one top-level declaration, and filename/type ownership across all four Gradle projects using Javac trees. Source and tests use typed domain values and all Gradle compilation runs pass with `-Xlint:all -Werror`. IntelliJ Rebuild Project completed without a visible build error. Whole-project inspection reported zero Java errors and zero Java file diagnostics. The original audit's only Java-category findings were three accepted project-model warnings for the intentional `mcp-tool-api.main` dependency in the synthetic benchmark, conformance, and runtime-test modules; the continuation replaces benchmark and conformance with ordinary consumer projects. Generated/build/report inspection noise was excluded from the Java result. |
| Original checkout, preservation stash, branch, and remote target | PASS | Original `master` is clean at `7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6`. The preservation stash is `724872e927c8d4f3dd2290d4df4c4e94fe655e9b` (`preserve-bun-ts7-dependency-experiment-before-java-runtime-task`). Immediately before this document was created, the production snapshot was clean on `codex/java25-indexer-callgraph` and equal to its origin tracking branch. The worktree then became intentionally untracked-dirty only for `docs/verification/2026-07-10-pure-java-acceptance.md`. No merge, tag, release, npm deprecation, or worktree deletion occurred. |

## Verification Run Ledger

### Java identities

```text
Java 25: Eclipse Temurin 25.0.3+9-LTS, OpenJDK 64-Bit Server VM
Java 26: Eclipse Temurin 26.0.1+8, OpenJDK 64-Bit Server VM
Gradle: 9.7.0
Compilation target: --release 25, no preview features
```

### Repository-owned checks

The controller recorded these successful checks before this acceptance document
was created:

```powershell
.\gradlew.bat clean test shadowJar runtimeTestBundle benchmarkBundle benchmarkClasses java25ArtifactBundle releaseVerifierTest --no-configuration-cache --console=plain
.\gradlew.bat check cutoverCheck runtimeArtifactSmoke --no-configuration-cache --console=plain
.\gradlew.bat test -PtestJavaVersion=26 --rerun-tasks --no-configuration-cache --console=plain
.\scripts\build-mcpb.ps1 -Jar build\libs\mcdev-mcp-3.0.0.jar
.\scripts\verify-release-assets.ps1 -DryRun -Version 3.0.0 -Directory build\distributions
```

Recorded result: 456 root tests plus one `mcp-tool-api` test on each Java
correctness matrix, with zero failures, errors, or skips. `check`,
`cutoverCheck`, the runtime-artifact smoke, MCPB schema/extraction/launch smoke,
and positive/negative release-verifier tests passed. The same build-once JAR was
run successfully under Java 25 and Java 26. Those runs predated the final
documentation/cutover commit, so the current-snapshot hash and repeat results
below are the authoritative release-artifact evidence.

The current clean aggregate ran the Java 25 correctness suite. Its JUnit XML
reports 464 root tests in 67 suites plus one `mcp-tool-api` test, with zero
failures, errors, or skips. The additional eight root tests are the final
cutover/environment, index thread, and path validation regressions added after
the earlier 456-test Task 15 matrix.

The current-snapshot Java 26 repeat ran:

```powershell
.\gradlew.bat test -PtestJavaVersion=26 --rerun-tasks --no-configuration-cache --console=plain
```

Its JUnit XML likewise reports 464 root tests in 67 suites plus one
`mcp-tool-api` test, with zero failures, errors, or skips.

The current clean aggregate and distribution gates ran:

```powershell
.\gradlew.bat clean check cutoverCheck shadowJar runtimeTestBundle benchmarkClasses generateMcpbManifest conformanceHarnessJar --no-configuration-cache
.\scripts\run-conformance.ps1
.\scripts\build-mcpb.ps1 -Jar build\libs\mcdev-mcp-3.0.0.jar
.\scripts\verify-release-assets.ps1 -DryRun -Version 3.0.0 -Directory build\distributions
```

The aggregate Gradle build passed, including dependency, cutover, SDK,
release-verifier, shaded-JAR, runtime-bundle, benchmark-classes, manifest, and
test-only conformance-harness JAR gates. Official conformance passed against
the test-only harness launched directly with its recorded Java 25 executable.
MCPB schema validation, packing, extraction, and launch smoke passed, and the
extracted inner-JAR SHA matched the build-once shaded JAR. The release verifier
dry run passed.

The Task 15 workflow audit additionally passed `actionlint` 1.7.12 and
PowerShell AST parsing for the release, benchmark, conformance, MCPB, and
release-verifier scripts.

The precompiled `runtimeTestBundle` harness then ran the same JAR bytes under
both runtimes without invoking Gradle or recompiling:

```powershell
& $java25 --illegal-native-access=deny -cp $runtimeClasspath dev.mcdevmcp.packaging.RuntimeArtifactSmokeMain $runtimeJar
& $java26 --illegal-native-access=deny -cp $runtimeClasspath dev.mcdevmcp.packaging.RuntimeArtifactSmokeMain $runtimeJar
```

```text
RUNTIME_JAVA feature=25 vendor=Eclipse Adoptium vm=OpenJDK 64-Bit Server VM
RUNTIME_ARTIFACT_SMOKE_OK
RUNTIME_JAVA feature=26 vendor=Eclipse Adoptium vm=OpenJDK 64-Bit Server VM
RUNTIME_ARTIFACT_SMOKE_OK
```

Both executions used SHA-256
`c85d2191ca303d929426f47caeb1fd173266dd056d8be53f7d7b74bceff8e898`
and covered manifest/signature/service validation, H2 persistence, Tiny
Remapper, Vineflower decompile-and-compile, CLI, STDIO initialize, and the
Java-owned `tools/list` catalog.

### Current-snapshot artifact

At audit time the clean build produced:

```text
Artifact: build/libs/mcdev-mcp-3.0.0.jar
SHA-256: c85d2191ca303d929426f47caeb1fd173266dd056d8be53f7d7b74bceff8e898
Main-Class: dev.mcdevmcp.app.Main
Implementation-Version: 3.0.0
```

The same SHA was present in `build/distributions/mcdev-mcp-3.0.0.jar`, its
`.jar.sha256` sidecar, and the streamed `mcdev-mcp.jar` entry inside the packed
MCPB. Direct archive inspection found exactly `bootstrap.cjs`, `manifest.json`,
and `mcdev-mcp.jar`. The release dry-run verifier returned
`Verified dry run assets for 3.0.0` for this artifact set.

### CI state

GitHub Actions run `31696648322` completed successfully for exact commit
`64d5149bf7f054a2b98656d2ffbb52607dc17117`. Its build-once Java 25 job,
official MCP conformance 0.1.16 job, downloaded-artifact MCPB pack/extracted
smoke job, Ubuntu exact-JAR runtime jobs on Java 25 and Java 26, and macOS
exact-JAR runtime job on Java 25 all passed.

### Focused static evidence gathering

These read-only checks ran against the audited snapshot:

```powershell
git diff --check
git status --short --branch
git -C <original-checkout> status --short --branch
git rev-list --left-right --count origin/codex/java25-indexer-callgraph...HEAD
git stash list
jar tf build/libs/mcdev-mcp-3.0.0.jar
.\scripts\verify-release-assets.ps1 -DryRun -Version 3.0.0 -Directory build\distributions
```

Results: no whitespace errors; before the acceptance document was created the
production snapshot and original checkout were clean; local and remote codex
branch tips had zero divergence; and the preservation stash was present. The
worktree now reports the intentionally untracked `docs/verification` path.
Archive inspection produced the counts recorded in the matrix; the MCPB
inner-JAR SHA matched the release JAR and checksum sidecar; and the release dry
run passed. Repository-relative paths and Git object IDs are retained; local
JDK, home, game, cache, log, and temporary paths are deliberately omitted.

## External Qualification State

### Full Minecraft corpora

**INCOMPLETE.** No accepted reports are available for either Minecraft 1.21.11
or 26.1. The following fields therefore remain intentionally blank rather than
inferred from fixtures:

| Version | Java | Workers | Parsed/expected units | Logical symbol hash | Logical callgraph hash | Probe result | Peak live heap | Peak RSS | Reviewed Node deltas |
|---|---:|---:|---:|---|---|---|---:|---:|---|
| 1.21.11 | 25 | 1 and up to 4 | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| 1.21.11 | 26 | 1 and up to 4 | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| 26.1 | 25 | 1 and up to 4 | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| 26.1 | 26 | 1 and up to 4 | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |

The frozen shared Node/Java binary projection format and its golden digest
`484633761215a973adcddf71bbe833a9b0e223db54428e6dbcf93f597532458e`
are tested, but they do not prove either real corpus. Qualification must use immutable,
hash-verified source/remapped-JAR inputs, reject incomplete provenance, run
under `-Xmx4g`, and attach the raw machine-readable reports before this item can
move to `PASS`.

### Benchmark policy

**INCOMPLETE/NEUTRAL.** Policy implementation is tested, but no accepted set of
three consecutive comparable scheduled runs is attached. A manual or advisory
run cannot establish preference. Until qualifying history exists, Java 25 and
Java 26 remain supported peers.

### Live DebugBridge

**INCOMPLETE.** No compatible live instance was available. Fixture-backed wire
and handler tests passed, but they do not substitute for the required live
`mc_connect`, snapshot, entity/block, screen, image, and wait-state checks.
This is a release-readiness blocker, not a waived test.

## Evidence Sources

- `build.gradle.kts` and the subproject build files: Java/toolchain/warning policy, project isolation,
  archive construction, runtime bundle, dependency policy, and cutover gates.
- `.github/workflows/ci.yml`, `.github/workflows/benchmark.yml`, and
  `.github/workflows/release.yml`: correctness matrix, corpus/benchmark policy,
  build-once provenance, and publication ordering.
- `scripts/run-conformance.ps1`, `scripts/build-mcpb.ps1`,
  `scripts/verify-release-assets.ps1`, and
  `scripts/test-verify-release-assets.ps1`: protocol, MCPB, and release gates.
- `src/test/java/dev/mcdevmcp` and `benchmark/src/test`: repository-owned unit,
  integration, parity, benchmark, corpus-harness, archive, dependency, indexer,
  callgraph, CLI, MCP, and DebugBridge evidence.
- `src/runtimeTest/java/dev/mcdevmcp/packaging/RuntimeArtifactSmokeMain.java`:
  exact-artifact cross-runtime checks.
- `src/test/resources/contracts`, `benchmark/src/test/resources/contracts`, and
  the immutable Node oracle materializer:
  frozen tool/resource and parity contracts.
- `.superpowers/sdd/progress.md` and task-specific review reports: historical
  task gates, independent reviews, runtime identities, and test counts.
- Git history from `master..64d5149`: logical implementation, packaging, CI,
  and cutover commits.

## Remaining Gates

The branch must not be called release-ready until all of the following are
recorded and independently reviewed:

1. Complete Minecraft 1.21.11 and 26.1 qualification reports described above.
2. Live DebugBridge v2.0.0 non-mutating smoke evidence.
3. An independent review of the interrupted Task 17 fixes and the continuation
   module/CI diff.
4. A clean reviewed acceptance-evidence commit and guarded branch push, without
   merging or publishing a release.
