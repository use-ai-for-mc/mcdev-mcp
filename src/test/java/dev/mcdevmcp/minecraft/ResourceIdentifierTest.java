package dev.mcdevmcp.minecraft;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceIdentifierTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void normalizesOmittedNamespaceAndPreservesExplicitNamespace() throws Exception {
        assertEquals("minecraft:diamond", MAPPER.readValue("\"diamond\"", ResourceIdentifier.class).value());
        assertEquals("mod:foo/bar_baz-1.2", MAPPER.readValue("\"mod:foo/bar_baz-1.2\"", ResourceIdentifier.class).value());
        assertEquals("a0_.-:a0/_.-", MAPPER.readValue("\"a0_.-:a0/_.-\"", ResourceIdentifier.class).value());
    }

    @Test
    void serializesCanonicalIdentifierAsScalarJsonString() throws Exception {
        ResourceIdentifier identifier = MAPPER.readValue("\"diamond\"", ResourceIdentifier.class);

        assertEquals("\"minecraft:diamond\"", MAPPER.writeValueAsString(identifier));
    }

    @Test
    void rejectsMalformedIdentifierScalarsDuringMapperConstruction() {
        List<String> malformedJson = List.of("\"\"", "\"Minecraft:diamond\"", "\"minecraft:Diamond\"", "\"..:diamond\"", "\":diamond\"", "\"minecraft:\"", "\"one:two:three\"", "\"minecraft:diamond?\"", "\"minecraft:diamond space\"");

        for (String json : malformedJson) {
            assertThrows(Exception.class, () -> MAPPER.readValue(json, ResourceIdentifier.class), json);
        }
    }

    @Test
    void rejectsNullDomainValuesAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceIdentifier(null));
    }
}
