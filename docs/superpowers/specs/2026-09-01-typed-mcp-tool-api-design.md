# Typed MCP Tool API Design

## Status

Approved by the user's 2026-09-01 direction. This design supersedes the
earlier package-organization rule that kept `ToolBinding`, cancellation, and
schema ownership in the root application. It does not supersede the pure-Java
server, packaging, parity, or DebugBridge requirements.

## Problem

The MCP Java SDK exposes tool arguments as `Map<String, Object>` and accepts
tool input schemas as `Map<String, Object>`. It does not associate that schema
with a Java target type or generate a schema from a Java record. The current
server fills part of the gap with `ArgumentDecoder<A>`, but most tools decode
into an `Object`-heavy `*WireArguments` record and then convert again into a
domain record. That compatibility layer preserves malformed JavaScript input
behavior at the cost of duplicated types and manual casts.

Only functional parity is required for invalid requests. Valid tool calls,
tool results, DebugBridge payloads, and user-visible behavior remain stable.

## Goals

- Make `mcp-tool-api` a generic SDK/JDK-only typed tool and MCP
  deserialization library.
- Couple a Java input type, its JSON object schema, and direct
  `McpJsonMapper` conversion in one server-side value.
- Generate ordinary MCP JSON Schema from Java record and enum metadata.
- Move generic binding, handler, cancellation, and blocking-executor behavior
  into `mcp-tool-api`.
- Deserialize valid MCP arguments directly into the handler's domain record.
- Replace raw strings, `Object`, and arbitrary-precision numeric compatibility
  values with enums, exact Java primitives/wrappers, `MinecraftVersion`,
  `Duration`, and validated domain values where their semantics are closed.
- Keep Java class identity out of client-visible JSON and JSON Schema.
- Keep DebugBridge as a separate process with its existing endpoint names and
  JSON payload shapes.

## Non-Goals

- Do not publish `mcp-tool-api` as a standalone release yet.
- Do not add a second JSON implementation, JSON tree, schema engine, or code
  generator.
- Do not transmit Java class names or enable Jackson class-name polymorphism.
- Do not change DebugBridge endpoint names, response shapes, or mod code.
- Do not preserve exact error text for requests that violate advertised JSON
  Schema.

## Generic API

`JsonObjectSchema` owns a deeply immutable, insertion-ordered JSON Schema map
whose root type is `object`. `InputProperty` supplies schema facts not encoded
by Java's type system: description, requiredness, numeric bounds, and a
documented default. A default is metadata only; record constructors remain
responsible for applying it.

`InputSchemaFactory` maps a Java type token to a `JsonObjectSchema`.
`RecordInputSchemaFactory` supports records composed from strings, booleans,
integral and decimal numbers, enums, nested records, arrays, and collections.
Unsupported or ambiguous types fail during catalog construction rather than
advertising an incomplete schema.

`ToolInput<A>` permanently couples `JsonType<A>` and `JsonObjectSchema`. Its
`decode` method performs the one conversion from the SDK argument map to `A`
through `McpJsonMapper`. Client JSON contains only the semantic tool arguments.

`ToolCancellation`, `ToolHandler<A>`, `BlockingToolHandler<A>`,
`ToolHandlers`, and `ToolBinding<A>` are generic execution contracts and move
to the library. Application availability rules, catalogs, transport adapters,
and Minecraft behavior remain in the root project.

## Schema Authority Transition

During migration, `tools.json` remains the frozen name, description, and
schema inventory. The catalog derives each schema from the binding's
`ToolInput<A>` and compares it with the checked-in schema. Each migrated tool
must pass this drift gate. After every tool is migrated, `inputSchema` is
removed from `tools.json`; Java input records become the only schema source.

## JSON And Jackson Policy

The official SDK's Jackson 3-backed `McpJsonMapper` remains the sole JSON
implementation. Jackson annotations already exposed through the reviewed SDK
dependency may define scalar creators, JSON values, and property names. They
do not authorize direct `ObjectMapper`, `JsonNode`, or Jackson-specific tree
use. Semantic discriminators are required for unions.

`MinecraftVersion` and similar scalar records use delegating creators so JSON
text maps directly to the validated value. Optional record components are
nullable strong types with convenience `Optional` accessors unless mapper
support for `Optional<T>` is explicitly proven.

## Runtime Type Policy

Actual DebugBridge provider signatures and checked-out Minecraft source are the
authority for runtime argument types. Runtime IDs, coordinates, slots, counts,
limits, frame counts, grid columns, and downscale factors are integral Java
types. Radii and media quality use binary floating-point types. Time uses
`Duration`. `BigDecimal`, `BigInteger`, and general `Number` are not runtime
input domain types.

Required and defaulted numeric/boolean values use primitives in the final
record. Same-record creator parameters may be boxed solely to distinguish an
omitted JSON property while materializing defaults, including default-true
booleans. A wrapper remains nullable in the final record only when absence is a
domain value, such as a missing port that requests scanning or an omitted
range/limit. Minecraft-specific values such as
resource identifiers, block positions, entity equipment slots, and server
addresses use validated root-project domain types; they do not belong in the
generic `mcp-tool-api` module.

The authoritative per-tool matrix and source findings live in
`.superpowers/sdd/2026-09-01-typed-mcp-tool-api/minecraft-runtime-argument-audit.md`.
It supersedes earlier migration tests or plan prose that modeled runtime
integers and time as arbitrary-precision numbers.

MCP input JSON may adopt a stronger semantic shape, such as a nested
`BlockPosition` or a tagged recording cadence. The typed JSON is decoded
directly, without a compatibility wire decoder. A named bridge payload record
then performs the explicit protocol projection while preserving DebugBridge
endpoint names and payload shape.

Every closed JSON boundary carries its target Java type in trusted server-side
metadata through `JsonType<T>`/SDK `TypeRef<T>` and `McpJsonMapper`; this
includes parameterized components such as `List<Path>`. Jackson property
metadata on the final Java type maps differing JSON field names directly. The
client-visible schema describes semantic JSON shape and optional formats, not
Java class names, and no handler manually converts raw strings or numbers into
a closed JDK/domain type after mapper conversion.

## Parity Policy

Valid requests retain functional results and unchanged DebugBridge endpoint and
payload semantics. MCP input shape may change where a typed domain value makes
the contract clearer. MCP schema validation rejects wrong JSON types before
handler deserialization. Tests that previously asserted JavaScript-style
malformed input coercion move to schema-rejection coverage. Missing, explicit
`null`, defaults, numeric bounds, and enum values receive focused tests.

`mc_find_refs` callers/callees semantics, descriptors, ordering, limits, and
output remain unchanged.

## Module Boundary

`mcp-tool-api` may depend only on JDK APIs and the reviewed `mcp-core` API
dependency. Its tests may use the SDK Jackson 3 provider and JUnit. It must not
depend on the root application, benchmark, conformance, Minecraft, H2,
Picocli, or DebugBridge classes.

The root project retains `ToolCatalog`, `ToolAvailability`, `ToolMetadata`,
`McpSdkAdapter`, environment policy, and every Minecraft-specific handler and
domain value.

## Acceptance

- `mcp-tool-api` builds independently as explicit module
  `dev.mcdevmcp.mcp.tool.api` on Java 26.
- JPMS smoke verifies the SDK Jackson 3 provider and direct typed decoding.
- No production binding uses `ArgumentDecoder.map` after migration.
- No `*WireArguments`, `TextArgument`, `ArgumentShape`, or `LimitInput` remains
  after all tools migrate.
- No runtime input record or numeric compatibility helper uses `BigDecimal`,
  `BigInteger`, or general `Number`.
- Runtime request projection uses named typed bridge payload values rather than
  assembling protocol maps in tool handlers.
- Closed runtime response objects deserialize directly into their final typed
  Java result values rather than being field-cast from raw maps.
- Every advertised schema is generated from the same type deserialized by its
  binding.
- Valid static and runtime tool contracts, bridge payloads, Java 26 tests,
  differential parity, MCP conformance, MCPB, and exact-JAR gates pass.
- IntelliJ reformat runs after every edit, and changed Java files have no
  warning-or-higher diagnostics.
