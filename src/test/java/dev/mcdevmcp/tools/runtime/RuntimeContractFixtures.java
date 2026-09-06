package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeResponse;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RuntimeContractFixtures {
    private static final String FROZEN_GAME_DIRECTORY = "C:\\Game";
    private static final boolean WINDOWS_HOST = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    private static final String NATIVE_GAME_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "mcdev-runtime-fixture-game").toAbsolutePath().normalize().toString();

    private RuntimeContractFixtures() {
    }

    static BridgeResponse status(String requestId) {
        var result = new LinkedHashMap<String, Object>();
        result.put("version", "1.21.11");
        result.put("mappingStatus", "mojang");
        result.put("obfuscated", true);
        result.put("refs", 7);
        result.put("webUiPort", 9976);
        result.put("gameDir", gameDirectory());
        result.put("latestLog", fixturePath("C:\\Game\\logs\\latest.log"));
        result.put("sessionControlEnabled", true);
        return new BridgeResponse(requestId, true, true, result, null, null);
    }

    static String gameDirectory() {
        return WINDOWS_HOST ? FROZEN_GAME_DIRECTORY : NATIVE_GAME_DIRECTORY;
    }

    static String fixturePath(String value) {
        Objects.requireNonNull(value, "value");
        return WINDOWS_HOST ? value : value.replace(FROZEN_GAME_DIRECTORY, NATIVE_GAME_DIRECTORY);
    }

    static Object nativeResult(Object value) {
        if (value instanceof String text) {
            return fixturePath(text);
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> copy = new LinkedHashMap<>();
            object.forEach((key, entry) -> copy.put((String) key, nativeResult(entry)));
            return copy;
        }
        if (value instanceof List<?> array) {
            return array.stream().map(RuntimeContractFixtures::nativeResult).toList();
        }
        return value;
    }

    static <T> List<T> load(McpJsonMapper mapper, String resource, Class<T> type) throws IOException {
        try (var input = RuntimeContractFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            return parse(Objects.requireNonNull(mapper, "mapper"), Objects.requireNonNull(input, resource).readAllBytes(), resource, type);
        }
    }

    static <T> List<T> parse(McpJsonMapper mapper, byte[] bytes, String resource, Class<T> type) throws IOException {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(type, "type");
        String contents = decodeStrictUtf8(bytes, resource);
        List<T> documents = new ArrayList<>();
        String[] lines = contents.split("\\n", -1);
        for (int index = 0; index < lines.length - 1; index++) {
            int lineNumber = index + 1;
            String line = lines[index];
            validateLine(line, lineNumber, resource);
            try {
                documents.add(mapper.readValue(line, type));
            } catch (IOException | RuntimeException exception) {
                throw new IOException("Invalid JSONL object at line " + lineNumber + " in " + resource, exception);
            }
        }
        return List.copyOf(documents);
    }

    private static String decodeStrictUtf8(byte[] bytes, String resource) throws IOException {
        if (bytes.length == 0) {
            throw new IOException("JSONL resource is empty: " + resource);
        }
        int trailingLineFeeds = 0;
        for (int index = bytes.length - 1; index >= 0 && bytes[index] == '\n'; index--) {
            trailingLineFeeds++;
        }
        if (trailingLineFeeds != 1) {
            throw new IOException("JSONL resource must end with exactly one LF: " + resource);
        }
        for (byte value : bytes) {
            if (value == '\r') {
                throw new IOException("CR bytes are forbidden in JSONL resource: " + resource);
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 in JSONL resource: " + resource, exception);
        }
    }

    private static void validateLine(String line, int lineNumber, String resource) throws IOException {
        if (line.isEmpty()) {
            throw new IOException("Blank JSONL line " + lineNumber + " in " + resource);
        }
        if (!line.startsWith("{") || !line.endsWith("}") || containsWhitespaceOutsideString(line)) {
            throw new IOException("JSONL line " + lineNumber + " must contain one compact JSON object in " + resource);
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                }
                else if (character == '\\') {
                    escaped = true;
                }
                else if (character == '"') {
                    quoted = false;
                }
            }
            else if (character == '"') {
                quoted = true;
            }
            else if (character == '{' || character == '[') {
                depth++;
            }
            else if (character == '}' || character == ']') {
                depth--;
                if (depth < 0 || (depth == 0 && index != line.length() - 1)) {
                    throw new IOException("JSONL line " + lineNumber + " must contain one compact JSON object in " + resource);
                }
            }
        }
        if (quoted || depth != 0) {
            throw new IOException("JSONL line " + lineNumber + " must contain one compact JSON object in " + resource);
        }
    }

    private static boolean containsWhitespaceOutsideString(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                }
                else if (character == '\\') {
                    escaped = true;
                }
                else if (character == '"') {
                    quoted = false;
                }
            }
            else if (character == '"') {
                quoted = true;
            }
            else if (Character.isWhitespace(character)) {
                return true;
            }
        }
        return false;
    }
}
