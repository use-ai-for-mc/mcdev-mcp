# Typed MCP Tool API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `mcp-tool-api` the generic typed schema/deserialization layer missing from the MCP Java SDK and migrate
every server tool to direct typed argument records.

**Architecture:** Java record metadata generates ordinary MCP JSON Schema and `ToolInput<A>` couples that schema with
the exact `JsonType<A>` decoded by the SDK mapper. Generic binding and cancellation contracts live in `mcp-tool-api`;
the root keeps catalogs, transport, availability, and Minecraft behavior. Migration preserves valid behavior and
DebugBridge JSON while schema-invalid compatibility cases become boundary rejection tests.

**Tech Stack:** Java 26 language and bytecode, Java 26 tests, Gradle 9.7.1, MCP Java SDK 2.0.1, SDK Jackson
3-backed `McpJsonMapper`, JUnit 6.1.3, IntelliJ MCP.

**Spec:** `docs/superpowers/specs/2026-09-01-typed-mcp-tool-api-design.md`

**Runtime type audit:**
`.superpowers/sdd/2026-09-01-typed-mcp-tool-api/minecraft-runtime-argument-audit.md`

## Global Constraints

- Use `McpJsonMapper` as the sole JSON implementation; do not use direct `ObjectMapper`, `JsonNode`, Gson, or another
  JSON/schema engine.
- Keep Java type identity in server-side metadata only; never emit FQNs, `$javaType`, or Jackson class-name
  polymorphism.
- Add no production dependency beyond the existing `mcp-core` API dependency.
- Keep DebugBridge endpoint names and serialized payload shapes unchanged.
- Preserve valid-request functional parity; schema-invalid JavaScript coercion and exact error text may be removed.
- Derive runtime numeric/domain types from DebugBridge provider signatures and checked-out Minecraft source. Do not use
  `BigDecimal`, `BigInteger`, or general `Number` in runtime input records.
- Store boolean/numeric defaults as primitives after same-record creators materialize omitted JSON values. Retain
  nullable wrappers only when absence remains meaningful in the final domain value.
- Keep every named top-level declaration in its own matching Java file.
- Reformat every edited file with IntelliJ MCP and run warnings-enabled IntelliJ diagnostics for every changed Java file
  before review.
- Use TDD, one implementer and an independent reviewer per task, Java 26 verification, and the pinned Node oracle.

---

### Task 1: Add Typed Input Schema And Direct Decoding

**Files:**

- Create: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/JsonObjectSchema.java`
- Create: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/InputProperty.java`
- Create: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/InputSchemaFactory.java`
- Create: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/RecordInputSchemaFactory.java`
- Create: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/ToolInput.java`
- Create focused tests and top-level test fixtures in `mcp-tool-api/src/test/java/dev/mcdevmcp/mcp/tool/api/`
- Modify: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/JsonType.java`
- Modify: `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/package-info.java`

**Interfaces:**

- Produces: `JsonObjectSchema.of(Map<String,Object>)`, `InputSchemaFactory.generate(JsonType<?>)`,
  `RecordInputSchemaFactory.standard()`, `ToolInput.of(Class<A>, InputSchemaFactory)`, and
  `ToolInput.decode(McpJsonMapper, Map<String,Object>)`.
- `InputProperty` has `description`, `required`, `minimum`, `maximum`, and `defaultValue` string members with empty
  defaults.

- [x] Write failing tests proving a record with string, boolean, `BigDecimal`, enum, optional, required, bounds, and
  defaults generates the expected deeply immutable object schema.
- [x] Write a failing test proving `ToolInput` decodes the complete map directly into that same record using
  `McpJsonMapper`.
- [x] Run
  `./gradlew :mcp-tool-api:test --tests '*ToolInputTest' --tests '*RecordInputSchemaFactoryTest' --no-configuration-cache --console=plain`
  and verify the tests fail because the API is absent.
- [x] Implement the minimal API. Reject non-record roots, raw `Object`, maps, unbounded wildcards, unsupported
  parameterized types, duplicate JSON property names, invalid decimal bounds, and a maximum below minimum.
- [x] Preserve property declaration order and emit `required` in record-component order. Omit empty description, bounds,
  default, and required arrays.
- [x] Run IntelliJ reformat and warnings-enabled diagnostics for all Task 1 files.
- [x] Run `./gradlew :mcp-tool-api:clean :mcp-tool-api:check --no-configuration-cache --console=plain` on Java 25 and
  repeat `:mcp-tool-api:test` with `-PtestJavaVersion=26`.
- [x] Commit with message `feat(api): add typed MCP tool inputs`.

### Task 2: Move Generic Binding Execution Into The Library

**Files:**

- Move generic equivalents of `Cancellation`, `ToolHandler`, `BlockingToolHandler`, `ToolHandlers`, and `ToolBinding`
  into `mcp-tool-api/src/main/java/dev/mcdevmcp/mcp/tool/api/`.
- Modify all root imports and the API module JPMS smoke.
- Add focused library tests for cancellation, blocking execution, failure propagation, future cancellation, and typed
  decode-before-handle order.

**Interfaces:**

- Produces: `ToolCancellation`, `ToolHandler<A>`, `BlockingToolHandler<A>`, `ToolHandlers`, and `ToolBinding<A>` whose
  ordinary constructor consumes `ToolInput<A>`.
- Keeps `ArgumentDecoder<A>` only as an explicitly named compatibility factory until all tools migrate.

- [ ] Write failing library tests for direct typed invocation and blocking cancellation.
- [ ] Move the generic contracts without importing any root package.
- [ ] Adapt root catalogs and transports without changing their public MCP behavior.
- [ ] Run IntelliJ reformat/diagnostics, `:mcp-tool-api:check`, root focused binding/adapter tests, and Java 26 repeats.
- [ ] Commit with message `refactor(api): extract typed tool binding execution`.

### Task 3: Add The Catalog Schema Drift Gate

**Files:**

- Modify: `src/main/java/dev/mcdevmcp/mcp/tool/ToolMetadata.java`
- Modify: `src/main/java/dev/mcdevmcp/mcp/tool/ToolDefinition.java`
- Modify: `src/main/java/dev/mcdevmcp/mcp/tool/ToolCatalog.java`
- Modify catalog/SDK adapter tests.

**Interfaces:**

- Consumes: `ToolBinding.input()` and `ToolInput.schema()`.
- Produces: catalog construction that fails when checked-in metadata schema differs from the generated typed schema.

- [ ] Write a failing catalog test with a mismatched property type and required list.
- [ ] Make the binding's generated schema authoritative in `ToolDefinition` while comparing it to `tools.json` during
  transition.
- [ ] Run catalog, adapter, tools/list, manifest, IntelliJ, and Java 26 tests.
- [ ] Commit with message `feat(mcp): enforce typed schema drift checks`.

### Task 4: Migrate Simple Runtime Tool Inputs

**Files:**

- Modify the runtime argument records and bindings for nearby entities, entity details, nearby blocks, block details,
  looked-at entity, chat history, screen inspect, screenshot, item textures, glow tools, and run command.
- Delete their matching `*WireArguments` records after focused tests pass.

**Interfaces:**

- Produces direct `ToolInput<DomainArguments>` bindings with source-exact primitive numbers, booleans, enums,
  `BlockPosition`, `ResourceIdentifier`, and validated scalar records.
- Preserves DebugBridge endpoint and payload shape plus semantic values; canonical scalar spellings are permitted when
  they preserve provider behavior.

- [ ] Replace invalid-wire compatibility assertions with schema rejection tests while retaining valid request/result
  fixtures.
- [ ] Replace `BigDecimal` expectations for IDs, coordinates, slots, counts, limits, radii, and media parameters with
  source-backed integer/double/domain expectations before migrating more handlers.
- [ ] Add bridge fixture assertions for every migrated payload.
- [ ] Migrate one tool family at a time, running its focused tests and IntelliJ reformat after each edit batch.
- [ ] Run all runtime contract, bridge, MCP STDIO, Java 26, and differential valid-request parity tests.
- [ ] Commit with message `refactor(runtime): deserialize typed tool inputs directly`.

### Task 5: Migrate Defaulted Runtime Inputs

**Files:**

- Modify connect, execute, join, quit, wait, and script-log argument records and bindings.
- Add scalar JSON support to existing validated values where required.
- Delete the matching wire records.

**Interfaces:**

- Applies defaults in compact constructors or delegating creators; JSON Schema `default` remains descriptive only.
- Uses `Duration` or focused timeout values after deserialization while preserving bridge milliseconds/seconds.
- Uses `MinecraftServerAddress`, exact integer limits, and primitives except for fields whose omitted value differs from
  explicit false/zero.

- [ ] Add failing tests separating missing, explicit null, wrong type, lower/upper bounds, and defaults.
- [ ] Implement direct typed records and unchanged bridge serialization.
- [ ] Run focused, runtime, STDIO, IntelliJ, Java 26, and parity gates.
- [ ] Commit with message `refactor(runtime): type defaulted MCP inputs`.

### Task 6: Migrate Static Tool Inputs And Domain Values

**Files:**

- Modify all static argument records, enums, bindings, and handlers.
- Modify: `src/main/java/dev/mcdevmcp/storage/model/MinecraftVersion.java`
- Delete static wire records, `TextArgument`, `ArgumentShape`, `LimitInput`, and duplicate enum-text fields.

**Interfaces:**

- Uses nullable `MinecraftVersion`, direct enums, integer limits, and required strings.
- Preserves all valid static outputs and complete `mc_find_refs` caller/callee behavior.

- [ ] Add scalar `MinecraftVersion` mapper tests and typed enum/limit schema tests.
- [ ] Migrate each static tool with valid-result parity and schema-rejection coverage.
- [ ] Run static contracts, full differential parity, index/callgraph suites, IntelliJ, and Java 26.
- [ ] Commit with message `refactor(static): deserialize typed tool inputs directly`.

### Task 7: Replace The Record-Video Union

**Files:**

- Modify `RecordInterval`, record-video arguments, schema metadata, binding, and tests.
- Delete `RecordVideoWireArguments`.

**Interfaces:**

- Produces a sealed semantic interval value using a `kind` discriminator with `frame` and `fixed` variants. The fixed
  variant contains `Duration intervalSeconds` and accepts numeric seconds at least `0.001`.
- Serializes the same DebugBridge interval value expected by the mod.

- [ ] Write failing schema and decode tests for both variants and unknown discriminators.
- [ ] Implement semantic polymorphism without class-name metadata.
- [ ] Run record-video, bridge, MCP, IntelliJ, Java 26, and parity gates.
- [ ] Commit with message `refactor(runtime): type record video intervals`.

### Task 8: Remove Transitional Schema, Decoder, Payload, And Content Scaffolding

**Files:**

- Remove `inputSchema` bodies from `src/main/resources/mcp/tools.json` after generated equality passes for every tool.
- Remove production `ArgumentDecoder.map` use and then the compatibility method if no tests or consumers need it.
- Replace raw runtime request maps with named typed bridge payload records, flattening domain values only at that
  protocol boundary.
- Replace closed runtime response-map extraction with direct `McpJsonMapper`
  deserialization into final typed result records, using `JsonType`/`TypeRef`
  metadata for JDK and parameterized values such as `Path` and `List<Path>`.
- Replace the nullable custom `ToolContent`/`ToolContentType` adapter with direct SDK `McpSchema.Content` values,
  including `McpSchema.ImageContent` for texture results.
- Update architecture, package organization, and acceptance documentation.

**Interfaces:**

- Java input records are the sole schema and deserialization authority.
- Named bridge payload records are the sole runtime request serialization authority.
- `ToolResult` carries SDK content values directly; image data remains protocol-required base64 plus MIME type.
- `tools.json` retains names and long descriptions only unless those also move into typed definitions during review.

- [ ] Add a whole-catalog test proving every enabled tool has a generated schema and direct typed binding.
- [ ] Add a source-layout test rejecting production `*WireArguments`, raw `Object` argument components, and
  `ArgumentDecoder.map` calls.
- [ ] Add source-layout and payload tests rejecting runtime `BigDecimal`/`BigInteger` inputs and raw request-map
  assembly.
- [ ] Remove transitional metadata and compatibility types.
- [ ] Run independent module builds, JPMS smoke, full Java 26 checks, differential parity, conformance, MCPB,
  exact-JAR runtime, release verifier, IntelliJ whole-project build/inspection, and cutover checks.
- [ ] Commit with message `refactor(api): make Java tool inputs authoritative`.
- [ ] Dispatch an independent whole-branch review and address every Critical or Important finding before push.
