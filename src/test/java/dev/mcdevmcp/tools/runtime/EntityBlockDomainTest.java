package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.bridge.payload.SetBlockGlowPayload;
import dev.mcdevmcp.bridge.payload.SetEntityGlowPayload;
import dev.mcdevmcp.mcp.tool.CountingMcpJsonMapper;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityBlockDomainTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void exposesSourceExactEntityPrimitivesAndNestedBlockPositionSchema() throws Exception {
        try (var harness = harness()) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER);

            ToolInput<?> entityDetails = bindings.get("mc_entity_details").input();
            assertEquals(int.class, Objects.requireNonNull(entityDetails.type().rawClass()).getRecordComponents()[0].getType());
            assertEquals(Map.of("type", "object", "properties", Map.of("entityId", Map.of("type", "integer", "description", "Entity id from mc_nearby_entities or mc_looked_at_entity.")), "required", List.of("entityId"), "additionalProperties", false), entityDetails.schema().value());

            ToolInput<?> entityGlow = bindings.get("mc_set_entity_glow").input();
            assertEquals(List.of(int.class, boolean.class), componentTypes(entityGlow));
            assertEquals(Map.of("type", "object", "properties", Map.of("entityId", Map.of("type", "integer", "description", "Entity id from mc_nearby_entities."), "glow", Map.of("type", "boolean", "description", "true to outline, false to remove.")), "required", List.of("entityId", "glow"), "additionalProperties", false), entityGlow.schema().value());

            ToolInput<?> blockDetails = bindings.get("mc_block_details").input();
            Class<?> blockPosition = Class.forName("dev.mcdevmcp.minecraft.BlockPosition");
            assertEquals(List.of(blockPosition), componentTypes(blockDetails));
            assertEquals(blockPosition, Objects.requireNonNull(blockDetails.type().rawClass()).getRecordComponents()[0].getType());
            assertEquals(Map.of("type", "object", "properties", Map.of("position", positionSchema()), "required", List.of("position"), "additionalProperties", false), blockDetails.schema().value());
            assertEquals(List.of(int.class, int.class, int.class), List.of(blockPosition.getRecordComponents()[0].getType(), blockPosition.getRecordComponents()[1].getType(), blockPosition.getRecordComponents()[2].getType()));

            ToolInput<?> blockGlow = bindings.get("mc_set_block_glow").input();
            assertEquals(List.of(blockPosition, boolean.class), componentTypes(blockGlow));
            assertEquals(Map.of("type", "object", "properties", Map.of("position", positionSchema(), "glow", Map.of("type", "boolean", "description", "true to highlight, false to remove this position.")), "required", List.of("position", "glow"), "additionalProperties", false), blockGlow.schema().value());
        }
    }

    @Test
    void decodesDomainRecordsDirectlyAndFlattensOnlyAtBridgeBoundary() throws Exception {
        try (var harness = harness()) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER);

            CountingMcpJsonMapper entityMapper = new CountingMcpJsonMapper(MAPPER);
            Object entity = bindings.get("mc_entity_details").input().decode(entityMapper, Map.of("entityId", 42));
            assertEquals(42, accessor(entity, "entityId"));
            assertEquals(1, entityMapper.convertValueCalls());

            CountingMcpJsonMapper blockMapper = new CountingMcpJsonMapper(MAPPER);
            Object block = bindings.get("mc_block_details").input().decode(blockMapper, Map.of("position", position()));
            Object decodedPosition = accessor(block, "position");
            assertEquals(1, accessor(decodedPosition, "x"));
            assertEquals(64, accessor(decodedPosition, "y"));
            assertEquals(-2, accessor(decodedPosition, "z"));
            assertEquals(1, blockMapper.convertValueCalls());

            bindings.get("mc_set_entity_glow").invoke(MAPPER, Map.of("entityId", 7, "glow", true), Cancellation.none()).toCompletableFuture().join();
            bindings.get("mc_set_block_glow").invoke(MAPPER, Map.of("position", position(), "glow", true), Cancellation.none()).toCompletableFuture().join();
            assertEquals(new SetEntityGlowPayload(7, true), harness.requests().get(1).payload());
            assertEquals(new SetBlockGlowPayload(1, 64, -2, true), harness.requests().get(2).payload());
        }
    }

    @Test
    void rejectsInvalidEntityAndBlockInputBeforeMapperOrBridgeDispatch() {
        try (var harness = harness()) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER);
            List<Map.Entry<String, Map<String, Object>>> invalid = List.of(Map.entry("mc_entity_details", map("entityId", 1.5)), Map.entry("mc_entity_details", map("entityId", "7")), Map.entry("mc_entity_details", map("entityId", null)), Map.entry("mc_entity_details", map("entityId", 7, "unknown", true)), Map.entry("mc_block_details", map("position", map("x", 1.5, "y", 2, "z", 3))), Map.entry("mc_block_details", map("position", map("x", 1, "y", 2, "z", 3, "unknown", true))), Map.entry("mc_block_details", map("position", null)), Map.entry("mc_block_details", map("x", 1, "y", 2, "z", 3)), Map.entry("mc_set_block_glow", map("position", map("x", 1, "y", 2, "z", 3), "glow", null)));
            for (Map.Entry<String, Map<String, Object>> testCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> bindings.get(testCase.getKey()).invoke(mapper, testCase.getValue(), Cancellation.none()), testCase.getKey());
                assertEquals(0, mapper.convertValueCalls(), testCase.getKey());
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    private static BridgeTestHarness harness() {
        return new BridgeTestHarness(MAPPER, new AppEnvironment(Map.of()), (_, request) -> {
            if (request.endpoint().wireName().equals("status")) {
                return CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            }
            Map<String, Object> payload = MAPPER.convertValue(request.payload(), new io.modelcontextprotocol.json.TypeRef<>() {
            });
            return CompletableFuture.completedFuture(new dev.mcdevmcp.bridge.BridgeResponse(request.id(), true, true, payload, null, null));
        });
    }

    private static List<Class<?>> componentTypes(ToolInput<?> input) {
        var types = new java.util.ArrayList<Class<?>>();
        for (var component : Objects.requireNonNull(input.type().rawClass()).getRecordComponents()) {
            types.add(component.getType());
        }
        return List.copyOf(types);
    }

    private static Map<String, Object> position() {
        return Map.of("x", 1, "y", 64, "z", -2);
    }

    private static Map<String, Object> positionSchema() {
        return Map.of("type", "object", "properties", Map.of("x", Map.of("type", "integer"), "y", Map.of("type", "integer"), "z", Map.of("type", "integer")), "required", List.of("x", "y", "z"), "additionalProperties", false);
    }

    private static Object accessor(Object value, String name) throws Exception {
        Method accessor = value.getClass().getMethod(name);
        return accessor.invoke(value);
    }

    private static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }
}
