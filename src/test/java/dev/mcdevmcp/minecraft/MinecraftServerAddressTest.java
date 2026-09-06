package dev.mcdevmcp.minecraft;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftServerAddressTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void trimsAndPreservesTheServerAddressSpelling() throws Exception {
        MinecraftServerAddress address = MAPPER.readValue("\"  Example.test:25565  \"", MinecraftServerAddress.class);

        assertEquals("Example.test:25565", address.value());
        assertEquals("\"Example.test:25565\"", MAPPER.writeValueAsString(address));
    }

    @Test
    void acceptsOrdinaryPortsAndIpv6FormsWithoutCanonicalizingThem() {
        List<String> valid = List.of("example.test", "example.test:0", "example.test:65535", "example.test:", "[::1]", "[::1]:25565", "[::1]:", "2001:db8::1", "foo:bar:baz", "bücher.example");

        valid.forEach(value -> {
            MinecraftServerAddress address = new MinecraftServerAddress(value);
            assertEquals(value, address.value(), value);
        });
    }

    @Test
    void rejectsMalformedHostsPortsBracketsAndBounds() {
        List<String> malformed = List.of("", "   ", ":25565", "example.test:-1", "example.test:65536", "example.test:abc", "example.test:１２", "[", "[]", "[example.test]", "[::1", "[::1]trailing", "[::1]:65536", "[::1]:abc", "example[.test", "example].test");

        malformed.forEach(value -> assertThrows(IllegalArgumentException.class, () -> new MinecraftServerAddress(value), value));
    }

    @Test
    void enforcesPostTrimLengthAndNull() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftServerAddress(null));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftServerAddress(" ".repeat(257)));
        String maximum = "a.".repeat(128);
        assertEquals(maximum, new MinecraftServerAddress(maximum).value());
        assertThrows(IllegalArgumentException.class, () -> new MinecraftServerAddress("a".repeat(257)));
    }

    @Test
    void scalarJacksonCreatorAndValueRoundTrip() throws Exception {
        MinecraftServerAddress address = MAPPER.readValue("\"localhost:25565\"", MinecraftServerAddress.class);

        assertEquals(new MinecraftServerAddress("localhost:25565"), address);
        assertEquals("\"localhost:25565\"", MAPPER.writeValueAsString(address));
    }
}
