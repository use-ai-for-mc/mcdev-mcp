package dev.mcdevmcp.mcp.resource;

import dev.mcdevmcp.mcp.McpContractTestSupport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceCatalogTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private static String contractText(String contract) throws Exception {
        var result = MAPPER.convertValue(McpContractTestSupport.readContract(contract).get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        });
        return (String) result.get("contents").getFirst().get("text");
    }

    @Test
    void resourceListMatchesTheNodeContract() throws Exception {
        var catalog = new ResourceCatalog();
        var actual = catalog.definitions().stream().map(definition -> Map.<String, Object>of("uri", definition.uri().toString(), "name", definition.name(), "title", definition.title(), "description", definition.description(), "mimeType", definition.mimeType())).toList();
        var expected = MAPPER.convertValue(McpContractTestSupport.readContract("resources-list.json").get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        }).get("resources");

        assertEquals(McpContractTestSupport.normalize(expected), McpContractTestSupport.normalize(actual));
    }

    @Test
    void resourceContentsMatchReviewedContractsWithCanonicalLineEndings() {
        var catalog = new ResourceCatalog();
        var devLoop = catalog.read(URI.create("mcdev://guides/dev-loop")).text();
        var pythonScripting = catalog.read(URI.create("mcdev://guides/python-scripting")).text();

        assertAll(() -> assertEquals(contractText("resource-dev-loop.json"), devLoop), () -> assertEquals(contractText("resource-python-scripting.json"), pythonScripting), () -> assertFalse(devLoop.contains("\r"), "Resource responses must use platform-independent LF line endings"), () -> assertFalse(pythonScripting.contains("\r"), "Resource responses must use platform-independent LF line endings"));
    }

    @Test
    void pythonGuideNamesCurrentJavaSourcesWhilePreservingTheNodeOracle() throws Exception {
        var currentGuide = new ResourceCatalog().read(URI.create("mcdev://guides/python-scripting")).text();
        var oracleLink = "https://github.com/use-ai-for-mc/mcdev-mcp/blob/" + "7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6/src/tools/runtime/session.ts";

        assertFalse(currentGuide.contains(oracleLink));
        assertFalse(currentGuide.contains("src/tools/runtime/"));
        assertTrue(currentGuide.contains("dev.mcdevmcp.bridge.BridgeSession"));
        assertTrue(currentGuide.contains("dev.mcdevmcp.tools.runtime.McExecuteTool"));

        try (var oracleResource = ResourceCatalogTest.class.getResourceAsStream("/oracle/python-scripting-node.md")) {
            assertNotNull(oracleResource, "The frozen Node-visible Python guide must be retained separately");
            var frozenOracle = new String(oracleResource.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(frozenOracle.contains("](../src/tools/runtime/session.ts)"));
            assertNotEquals(frozenOracle, currentGuide);
        }
    }

    @Test
    void unknownResourceUsesTheNodeErrorText() {
        var exception = assertThrows(IllegalArgumentException.class, () -> new ResourceCatalog().read(URI.create("mcdev://guides/missing")));

        assertEquals("Unknown resource URI: mcdev://guides/missing", exception.getMessage());
    }
}
