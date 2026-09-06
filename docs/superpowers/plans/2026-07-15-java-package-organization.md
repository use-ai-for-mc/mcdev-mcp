# Java Package Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the current Java rewrite into shallow feature capsules, establish an extraction-ready typed MCP tool API, split every named top-level declaration into its own file, and reduce responsibility concentration without changing behavior.

**Architecture:** The repository is a four-project Gradle build. The root application still produces the only shaded server JAR; `mcp-tool-api` is a small explicit JPMS `java-library` consumed by the root; and the independently buildable `benchmark` and `conformance` harness projects consume the root without becoming production dependencies. Application packages are organized into MCP transport/tool/resource, class-file/source-index, and H2/model capsules. A build-scoped artifact transform supplies complete descriptors for the reviewed MCP SDK snapshot while the root release remains classpath-based.

**Tech Stack:** Java 25 language/bytecode, Java 25 and 26 test runtimes, Gradle 9.7.0, MCP Java SDK 2.0.1-SNAPSHOT, H2 2.4.240, JUnit 6.1.3, Javac compiler-tree APIs, IntelliJ MCP.

**Status:** Package reorganization complete. The later benchmark/conformance
harness-module and focused JPMS amendments are implemented and locally verified; independent
review and an authorized commit/push remain pending.

**2026-08-24 typed API amendment:** The initially narrow decoder project proved
too shallow after both static and runtime tool families adopted it. It is now
`mcp-tool-api`, and Task 1's original one-interface scope is superseded by a
cohesive typed boundary: `JsonType<T>` and `TypedJson<T>` associate raw JSON
with a `Class<T>` or `TypeRef<T>`; `ArgumentDecoder<A>` handles complete tool
argument objects; and content, ordinary results, and
`StructuredToolResult<T>` handle output. Java type names remain build/runtime
metadata and are not serialized into MCP JSON. Root-only execution,
cancellation, catalog, transport, and Minecraft policy remain unchanged.

## Global Constraints

- Work only in `C:\Users\ttski\Projects\mcdev-mcp\.worktrees\java25-indexer-callgraph` on `codex/java25-indexer-callgraph`; never edit, build, reset, clean, or commit in the original `master` checkout.
- Preserve the original Node oracle checkout, its exact commit, and the stash named `preserve-bun-ts7-dependency-experiment-before-java-runtime-task`.
- Preserve every existing MCP, indexing, H2, CLI, cancellation, concurrency, JSON, error, artifact, and process behavior. Package/class names and the internal Gradle project boundary are the only intended surface changes.
- Preserve the current IntelliJ-established formatting. Do not run broad formatting or rewrite unrelated whitespace.
- Compile every Java source set with Java 25, `options.release = 25`, `-Xlint:all`, and `-Werror`.
- Use top-level classes, interfaces, records, and enums by default. Every named top-level declaration gets one matching source file. Do not add nested types to avoid files.
- Do not make implementation helpers public solely to cross a cosmetic package boundary.
- Use Javac APIs, not regex, for Java source-layout enforcement.
- Do not add ArchUnit, Spring, a formatter, a source parser, or another production/runtime dependency. The build-only JPMS descriptor transform is the sole amendment.
- Keep `ArgumentDecoder<A>` whole-map and SDK-mapper-backed. Do not add a field-by-field typed-getter facade or a second JSON representation.
- `mcp-tool-api` may expose only JDK APIs and the narrow official MCP SDK module needed by its public API. A compile-only Servlet API is permitted solely to resolve the SDK core descriptor and must not become a runtime dependency. It must not depend on the root project or import application packages.
- `benchmark` and `conformance` depend on the root application. The root application and `mcp-tool-api` must not depend on either harness project.
- Keep `ToolBinding`, `Cancellation`, executor policy, catalogs, transport adaptation, and Minecraft behavior in the root application. Reusable JSON type tokens, typed JSON values, content, ordinary results, and structured results belong to `mcp-tool-api`.
- Name the explicit tool API module `dev.mcdevmcp.mcp.tool.api`; inject reviewed descriptors only for the broken MCP SDK artifacts used by that module, and do not add `--add-reads`/`--add-exports` workarounds or modularize the shaded root application.
- Run IntelliJ MCP `build_project` and warnings-enabled `get_file_problems` for every changed Java file before each review gate.
- Use one Terra/high implementer for broad package/visibility integration, then an independent Terra/high reviewer. Do not use Sol for mechanical relocation.

---

## Task 1: Establish The Extraction-Ready Binding Library

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `mcp-tool-api/build.gradle.kts`
- Move: `src/main/java/dev/mcdevmcp/mcp/ArgumentDecoder.java`
- Create: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/binding/package-info.java`
- Create: `mcp-tool-api/src/test/java/dev/mcdevmcp/mcp/binding/ArgumentDecoderTest.java`
- Create: `mcp-tool-api/src/test/java/dev/mcdevmcp/mcp/binding/WireArguments.java`
- Create: `mcp-tool-api/src/test/java/dev/mcdevmcp/mcp/binding/DomainArguments.java`
- Modify: current `ToolBinding` and tests that import `ArgumentDecoder`

**Interfaces:**
- Produces: `dev.mcdevmcp.mcp.tool.api.ArgumentDecoder<A>` with `sdk(Class<A>)`, `decode(McpJsonMapper, Map<String,Object>)`, and `map(Function<A,B>)`.
- Produces: independently buildable explicit `:mcp-tool-api` module named `dev.mcdevmcp.mcp.tool.api`.
- Consumes: `io.modelcontextprotocol.sdk:mcp-core:2.0.1-SNAPSHOT` as an API dependency.

- [x] **Step 1: Write the failing typed-decoder test**

Create `WireArguments.java` and `DomainArguments.java` as separate package-private records:

```java
record WireArguments(String uri, long timeoutMs) {}
```

```java
record DomainArguments(URI uri, Duration timeout) {}
```

Then create a child-project test that converts the complete map to the wire record and maps it to the domain record:

```java
class ArgumentDecoderTest {
    @Test
    void convertsTheCompleteArgumentMapAndThenMapsToDomainTypes() {
        var mapper = McpJsonDefaults.getMapper();
        var decoder = ArgumentDecoder.sdk(WireArguments.class)
                .map(arguments -> new DomainArguments(
                        URI.create(arguments.uri()),
                        Duration.ofMillis(arguments.timeoutMs())));

        var result = decoder.decode(mapper, Map.of(
                "uri", "https://example.test/tool",
                "timeoutMs", 1250L));

        assertEquals(
                new DomainArguments(
                        URI.create("https://example.test/tool"),
                        Duration.ofMillis(1250)),
                result);
    }
}
```

- [x] **Step 2: Configure the child project without production source**

Add `include("mcp-tool-api")`. Apply `java-library`; configure the Java 25 toolchain/release/lint flags and Java 25/26 test launcher; declare `mcp-core` as `api`, Jackson3 mapper only for tests, and JUnit; set the JAR manifest's `Automatic-Module-Name` exactly.

- [x] **Step 3: Run the red test**

Run:

```powershell
.\gradlew.bat :mcp-tool-api:test --console=plain
```

Expected: `compileTestJava` fails because `ArgumentDecoder` does not exist in the child package.

- [x] **Step 4: Move the minimal production API**

Move the existing interface unchanged except for package declaration:

```java
package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface ArgumentDecoder<A> {
    static <A> ArgumentDecoder<A> sdk(Class<A> type) {
        Objects.requireNonNull(type, "type");
        return (mapper, arguments) ->
                Objects.requireNonNull(mapper, "mapper").convertValue(arguments, type);
    }

    A decode(McpJsonMapper mapper, Map<String, Object> arguments);

    default <B> ArgumentDecoder<B> map(Function<A, B> converter) {
        Objects.requireNonNull(converter, "converter");
        return (mapper, arguments) -> converter.apply(decode(mapper, arguments));
    }
}
```

Add `implementation(project(":mcp-tool-api"))` to the root and update imports. Do not move application handler/result/cancellation types.

- [x] **Step 5: Run green and verify the boundary**

Run:

```powershell
.\gradlew.bat :mcp-tool-api:clean :mcp-tool-api:test :mcp-tool-api:jar --console=plain
jar --describe-module --file mcp-tool-api\build\libs\mcp-tool-api-3.0.0.jar
jdeps --ignore-missing-deps --recursive mcp-tool-api\build\libs\mcp-tool-api-3.0.0.jar
.\gradlew.bat :mcp-tool-api:dependencies --configuration runtimeClasspath --console=plain
```

Expected: tests pass; `jar` reports automatic module
`dev.mcdevmcp.mcp.tool.api`; `jdeps` contains no root application package; the
child runtime graph contains only its direct MCP core dependency and that
module's transitive graph, with no root-project output.

- [x] **Step 6: Run the root regression suite and commit**

Run:

```powershell
.\gradlew.bat clean test shadowJar cutoverCheck mcpSdkSnapshotCheck --console=plain
```

Commit only the child project, settings/root dependency, decoder move, and required imports:

```powershell
git commit -m "refactor: isolate MCP argument binding"
```

## Task 2: Organize MCP Application Packages

**Files:**
- Move: current MCP transport classes/tests to `dev.mcdevmcp.mcp.transport`
- Move: current application tool classes/tests to `dev.mcdevmcp.mcp.tool`
- Move: current resource classes/tests to `dev.mcdevmcp.mcp.resource`
- Modify: `McpServerFactory` and application/packaging imports
- Create: package documentation for each new production package

**Interfaces:**
- `dev.mcdevmcp.mcp.McpServerFactory` remains the composition facade.
- Transport owns only MCP SDK/STDIO adaptation.
- Tool owns application metadata, binding, handlers, result/content, availability, cancellation integration, and execution policy.
- Resource owns `ResourceCatalog`, `ResourceDefinition`, and `ResourceRead`.
- Cross-package construction is limited to
  `ResourceCatalog.withMapper(...)`, executor-aware `ToolCatalog.load(...)`,
  and `McpSdkAdapter.startStdio(...)`; other constructors/helpers remain
  non-public.

- [x] **Step 1: Capture the MCP baseline**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.mcp.*" shadowJar --console=plain
```

Record the passing test count and exact JAR process probes in the task report.

- [x] **Step 2: Move transport production and mirrored tests**

Move `McpSdkAdapter`, `StdioServer`, `NodeParityJsonMapper`, `EofTrackingInputStream`, and `NonClosingOutputStream`. Update package declarations/imports only, then run all MCP tests.

- [x] **Step 3: Move tool production and mirrored tests**

Move all application-specific `Tool*`, `BlockingToolHandler`, and `UnavailableToolArguments` types. Import `dev.mcdevmcp.mcp.tool.api.ArgumentDecoder` from the child. Keep all signatures and visibility unchanged, then run all MCP tests.

- [x] **Step 4: Move resources and update the factory**

Move the three resource types and tests, update `McpServerFactory`, and run the exact resource/process tests.

- [x] **Step 5: Verify and commit**

Run the Task 1 root regression command and IntelliJ inspections. Commit as:

```powershell
git commit -m "refactor: organize MCP application packages"
```

## Task 3: Move The Complete H2 Capsule

**Files:**
- Keep: `src/main/java/dev/mcdevmcp/storage/PlatformPaths.java`
- Keep: `src/main/java/dev/mcdevmcp/storage/model/*.java`
- Move to `src/main/java/dev/mcdevmcp/storage/h2/`: `AtomicH2Database.java`,
  `DatabaseBuilder.java`, `DatabaseFileHandle.java`,
  `DatabaseFileOperations.java`, `DatabaseLock.java`,
  `DatabaseLockDeadline.java`, `DatabaseLockState.java`,
  `DatabasePromotionPhase.java`, `DatabaseQuery.java`,
  `DatabaseValidator.java`, `H2DatabaseUrls.java`, `IndexCleaner.java`,
  `SymbolRepository.java`, `SymbolSchema.java`, and
  `VersionStateRepository.java`
- Move to `src/test/java/dev/mcdevmcp/storage/h2/`:
  `AtomicH2DatabaseTest.java`, `DatabaseLockProcessMain.java`,
  `DatabaseLockProcessTest.java`, `DatabaseLockTest.java`,
  `ForcedFallbackMoveStrategy.java`, `H2StorageContractTest.java`,
  `IndexCleanerTest.java`, `PausingDatabaseFileOperations.java`, and
  `SymbolSchemaTest.java`
- Keep with their owners: `PlatformPathsTest.java` and `ModelValueTest.java`
- Create: `src/main/java/dev/mcdevmcp/storage/h2/package-info.java`

**Interfaces:** Existing H2 database, lock, repository, schema, cleaner, validator, builder/query, URL, and lifecycle signatures remain unchanged.

- [x] **Step 1: Capture the storage baseline**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.storage.*" --console=plain
```

- [x] **Step 2: Move the production capsule as one visibility unit**

Move all H2-specific types together before compiling. Do not widen locks, file operations, URL helpers, promotion phases, or builders for access.

- [x] **Step 3: Move mirrored tests and update external imports**

Keep platform/model tests with their owning package. Update analysis and packaging imports without changing behavior.

- [x] **Step 4: Verify and commit**

Run storage tests, the root regression command, exact Java 25/26 H2 service probes, and IntelliJ inspections. Commit as:

```powershell
git commit -m "refactor: isolate H2 storage implementation"
```

## Task 4: Separate Class-File And Source-Index Capsules

**Files:**
- Move: `ClassFileType.java`, `ClassFileTypeCatalog.java`
- Rename: `DescriptorNames.java` to `ClassDescriptors.java`
- Create: `analysis/classfile/package-info.java`
- Keep: stable index request/result/facade/source-root types in `analysis/index`
- Create: `analysis/index/SourceIdentity.java`
- Create: `analysis/index/pipeline/SourceIndexPipeline.java`
- Move: all compiler/parsed/snapshot/writer/resolver internals and mirrored tests to `analysis/index/pipeline`
- Create: package documentation for stable and pipeline packages

**Interfaces:**

```java
public final class SourceIndexPipeline {
    public IndexSummary build(IndexRequest request) throws IndexBuildException;
}

public final class SourceIndexer {
    public IndexSummary build(IndexRequest request) throws IndexBuildException;
}
```

`SourceIndexer` delegates to one pipeline instance. `JavacSourceParser` becomes package-private. Parsed values remain package-private.

- [x] **Step 1: Capture all index baselines**

Run:

```powershell
.\gradlew.bat test --tests "dev.mcdevmcp.analysis.index.*" --console=plain
```

- [x] **Step 2: Move the class-file catalog and rename descriptors**

Move the catalog/types, rename the descriptor utility and its references, then run index tests.

- [x] **Step 3: Extract the stable pipeline boundary**

Move the current complete `SourceIndexer.build` orchestration into `SourceIndexPipeline.build` without reordering statements or changing exception handling. Make `SourceIndexer` a constructor-injected delegate and preserve its public no-argument constructor.

- [x] **Step 4: Extract `SourceIdentity` and move internals together**

Move the private `IndexRequest.SourceIdentity` record unchanged to a package-private matching file. Move all index internals and mirrored implementation tests as one package-private capsule.

- [x] **Step 5: Verify and commit**

Run index tests, the root regression command, no-fallback audit, Java 25/26 exact JAR probes, and IntelliJ inspections. Commit as:

```powershell
git commit -m "refactor: organize Java analysis packages"
```

## Task 5: Split Named Files And Coordinator Responsibilities

**Files:**
- Split: all named top-level declarations currently sharing production/test files
- Create: `JavacPreflight.java`, `JavacBatchParser.java`, `JavacDeclarationReader.java`, `JavacDiagnostics.java`, `JavacTaskExecutor.java`
- Create: one file per `SymbolIndexSnapshot` projection record
- Create: `H2DatabaseArtifacts.java`, `H2DatabasePromotion.java`

**Interfaces:** New collaborators remain package-private. `JavacSourceParser`, `SymbolIndexSnapshot`, and `AtomicH2Database` retain their external behavior and own orchestration.

- [x] **Step 1: Split projection and test-fixture declarations mechanically**

Move each declaration byte-for-byte except package/import adjustments. Run its focused test after each source file group.

- [x] **Step 2: Extract Javac responsibilities**

Move existing methods without semantic rewrites:

- preflight syntax/accounting/declaration discovery to `JavacPreflight`
- one compiler task to `JavacBatchParser`
- attributed declaration conversion to `JavacDeclarationReader`
- diagnostic classification/rendering to `JavacDiagnostics`
- future submission, cancellation polling, shutdown, and suppressed cleanup to `JavacTaskExecutor`

Preserve constants, compiler options, worker bounds, modular single-task behavior, source-text ownership, and close order.

- [x] **Step 3: Extract H2 artifact and promotion responsibilities**

Move temporary/backup/companion path and cleanup operations to `H2DatabaseArtifacts`. Move atomic/fallback promotion, validation, restore, and suppressed recovery handling to `H2DatabasePromotion`. Keep `AtomicH2Database.rebuild` as the public transaction/build orchestrator.

- [x] **Step 4: Verify and commit focused extractions**

Run index/storage focused suites after each extraction, then root regression and IntelliJ inspections. Use separate commits:

```powershell
git commit -m "refactor: split Java source declarations"
git commit -m "refactor: separate index pipeline responsibilities"
git commit -m "refactor: separate H2 lifecycle responsibilities"
```

## Task 6: Enforce Source Layout Across Both Projects

**Files:**
- Create: `src/test/java/dev/mcdevmcp/packaging/JavaSourceLayoutTest.java`
- Create: `package-info.java` for the existing `app`, root `mcp`, root
  `storage`, `storage.model`, and `support` packages

**Interfaces:** Test configured production/test source roots from both Gradle projects using `JavaCompiler`, `JavacTask.parse()`, and compiler trees. No regex source parsing.

- [x] **Step 1: Write a failing invariant test against a malformed fixture**

The test helper accepts a source root and verifies package/path agreement, exactly one named top-level declaration, and filename/simple-name agreement. `package-info.java` and `module-info.java` are zero-declaration exceptions. Add a temporary malformed fixture through `@TempDir` and observe each assertion fail for its intended reason.

- [x] **Step 2: Implement the Javac-tree scanner**

Use `ToolProvider.getSystemJavaCompiler()`, a diagnostic collector, and
`JavacTask.parse()` over sorted `.java` paths. Read `CompilationUnitTree.getPackageName()` and direct
`getTypeDecls()` entries whose kind is class, interface, enum, record, or
annotation type. Do not inspect source text with regex.

- [x] **Step 3: Point the invariant at every configured source root**

Cover root `src/main/java`, root `src/test/java`, child `src/main/java`, and
child `src/test/java`. Exclude build output and Java snippets stored as test
resources.

- [x] **Step 4: Run and commit**

Run both project tests and the root regression command. Commit as:

```powershell
git commit -m "test: enforce Java source layout"
```

## Task 7: Synchronize Plans And Repeat The Review Gate

**Files:**
- Modify: `docs/superpowers/specs/2026-07-10-pure-java-mcp-server-design.md`
- Modify: `docs/superpowers/plans/2026-07-10-pure-java-mcp-server.md`
- Modify: `.superpowers/sdd/progress.md` (ignored durable ledger)

- [x] **Step 1: Replace future paths with the implemented taxonomy**

Update MCP, storage, indexer, callgraph, DebugBridge, tool, packaging,
benchmark, and conformance paths. State the child dependency and staged JPMS
policy explicitly.

- [x] **Step 2: Run the complete verification matrix**

Run:

```powershell
.\gradlew.bat :mcp-tool-api:clean :mcp-tool-api:test :mcp-tool-api:jar clean test shadowJar cutoverCheck mcpSdkSnapshotCheck --console=plain
.\gradlew.bat :mcp-tool-api:test test shadowJar cutoverCheck mcpSdkSnapshotCheck -PtestJavaVersion=26 --console=plain
```

Also run exact shaded-JAR launches on Java 25/26, runtime dependency/archive audits, the Node oracle, no-fallback audit, IntelliJ full build, and warnings-enabled inspections for every changed Java file. Confirm original `master` and the preserved stash are unchanged.

- [x] **Step 3: Generate the review package and dispatch an independent reviewer**

Generate one package from the package-reorganization base commit through the
new head. Dispatch Terra/high with this plan, the approved package spec, the
implementation reports, and the review package. Resolve every Critical or
Important finding and repeat review before Task 6 of the main rewrite.

- [x] **Step 4: Commit synchronized documentation**

```powershell
git commit -m "docs: align Java rewrite with package organization"
```
