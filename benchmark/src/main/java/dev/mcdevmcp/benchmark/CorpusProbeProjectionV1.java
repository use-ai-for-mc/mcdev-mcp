package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Versioned semantic probe projection shared byte-for-byte with the frozen Node oracle.
 *
 * <p>SHA-256 input starts with {@code 0x06}, then nullable UTF-8 strings for the
 * schema, kind, and key, followed by the result list. A nullable string is
 * {@code 0x00} for null or {@code 0x01 + uint32be(length) + bytes}; a nullable
 * integer is {@code 0x00} for null or {@code 0x02 + int32be}; a list is
 * {@code 0x03 + uint32be(count)}. Every result row starts with {@code 0x04} and
 * every method parameter starts with {@code 0x05}. Fields are written in record
 * component order. Empty results and absent lists are encoded as empty lists,
 * while absent scalar values remain null.</p>
 */
public record CorpusProbeProjectionV1(String schema, CorpusProbeKind kind, String key, List<CorpusProbeProjectionRowV1> results) {
    public static final String SCHEMA = "mcdev-mcp-corpus-probe-v1";
    public static final int PROBE_REFERENCE_LIMIT = 100;

    public CorpusProbeProjectionV1 {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported corpus probe projection schema: " + schema);
        }
        Objects.requireNonNull(kind, "kind");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Corpus probe projection key must not be blank");
        }
        results = List.copyOf(results);
    }

    public static CorpusProbeProjectionV1 classProbe(String key, ClassSymbol symbol) {
        List<CorpusProbeProjectionRowV1> rows = symbol == null ? List.of() : List.of(CorpusProbeProjectionRowV1.classRow(symbol));
        return new CorpusProbeProjectionV1(SCHEMA, CorpusProbeKind.SYMBOL_CLASS, key, rows);
    }

    public static CorpusProbeProjectionV1 fieldProbe(String key, List<FieldSymbol> fields) {
        List<CorpusProbeProjectionRowV1> rows = fields.stream().map(CorpusProbeProjectionRowV1::fieldRow).toList();
        return new CorpusProbeProjectionV1(SCHEMA, CorpusProbeKind.SYMBOL_FIELD, key, rows);
    }

    public static CorpusProbeProjectionV1 methodProbe(String key, List<MethodSymbol> methods, Function<MethodSymbol, List<ParameterSymbol>> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        List<CorpusProbeProjectionRowV1> rows = methods.stream().map(method -> CorpusProbeProjectionRowV1.methodRow(method, parameters.apply(method))).toList();
        return new CorpusProbeProjectionV1(SCHEMA, CorpusProbeKind.SYMBOL_METHOD, key, rows);
    }

    public static CorpusProbeProjectionV1 referenceProbe(CorpusProbeKind kind, String key, List<MethodReference> references) {
        if (kind != CorpusProbeKind.CALLERS && kind != CorpusProbeKind.CALLEES) {
            throw new IllegalArgumentException("Reference projection requires CALLERS or CALLEES");
        }
        if (references.size() > PROBE_REFERENCE_LIMIT) {
            throw new IllegalArgumentException("Reference projection exceeds the frozen Node limit of " + PROBE_REFERENCE_LIMIT);
        }
        List<CorpusProbeProjectionRowV1> rows = new ArrayList<>(references.size());
        references.stream().map(CorpusProbeProjectionRowV1::referenceRow).sorted(CorpusProbeProjectionRowV1.REFERENCE_ORDER).forEach(rows::add);
        return new CorpusProbeProjectionV1(SCHEMA, kind, key, rows);
    }

    public String signature() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(6);
        string(output, schema);
        string(output, kind.name());
        string(output, key);
        list(output, results, CorpusProbeProjectionV1::row);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
    }

    private static void row(ByteArrayOutputStream output, CorpusProbeProjectionRowV1 value) {
        output.write(4);
        string(output, value.symbolKind());
        string(output, value.superclassBinaryName());
        list(output, value.interfaceBinaryNames(), CorpusProbeProjectionV1::string);
        string(output, value.sourcePath());
        string(output, value.memberName());
        string(output, value.memberType());
        string(output, value.returnType());
        list(output, value.parameters(), CorpusProbeProjectionV1::parameter);
        list(output, value.modifiers(), CorpusProbeProjectionV1::string);
        integer(output, value.lineStart());
        integer(output, value.lineEnd());
        string(output, value.referenceClassName());
        string(output, value.referenceMethodName());
        string(output, value.referenceDescriptor());
        integer(output, value.referenceLineNumber());
    }

    private static void parameter(ByteArrayOutputStream output, CorpusProbeParameterV1 value) {
        output.write(5);
        string(output, value.name());
        string(output, value.type());
    }

    private static void string(ByteArrayOutputStream output, String value) {
        if (value == null) {
            output.write(0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(1);
        output.writeBytes(int32(bytes.length));
        output.writeBytes(bytes);
    }

    private static void integer(ByteArrayOutputStream output, Integer value) {
        if (value == null) {
            output.write(0);
            return;
        }
        output.write(2);
        output.writeBytes(int32(value));
    }

    private static <T> void list(ByteArrayOutputStream output, List<T> values, ElementWriter<T> writer) {
        output.write(3);
        output.writeBytes(int32(values.size()));
        values.forEach(value -> writer.write(output, value));
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    @FunctionalInterface
    private interface ElementWriter<T> {
        @SuppressWarnings("unused")
        void write(ByteArrayOutputStream output, T value);
    }
}
