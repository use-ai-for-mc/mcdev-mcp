package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.bundle.JsonlLineReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the symbol index as one JSONL file: a header line, then every row in section order.
 */
public final class SymbolIndexFiles {
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final int MAXIMUM_LINE_BYTES = 1024 * 1024;
    private static final int INITIAL_SECTION_CAPACITY = 1 << 16;

    private SymbolIndexFiles() {
    }

    public static void write(Path file, SymbolIndexData data) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             OutputStream output = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024)) {
            line(output, data.header());
            section(output, data.packages());
            section(output, data.types());
            section(output, data.fields());
            section(output, data.methods());
            section(output, data.parameters());
            output.flush();
            channel.force(true);
        }
    }

    public static SymbolIndexData.Header readHeader(Path file) throws IOException {
        try (var reader = new JsonlLineReader(Files.newInputStream(file), MAXIMUM_LINE_BYTES)) {
            return header(file, reader);
        }
    }

    public static SymbolIndexData read(Path file) throws IOException {
        try (var reader = new JsonlLineReader(Files.newInputStream(file), MAXIMUM_LINE_BYTES)) {
            SymbolIndexData.Header header = header(file, reader);
            List<SymbolIndexData.PackageRow> packages = section(file, reader, header.packages(), SymbolIndexData.PackageRow.class);
            List<SymbolIndexData.TypeRow> types = section(file, reader, header.types(), SymbolIndexData.TypeRow.class);
            List<SymbolIndexData.FieldRow> fields = section(file, reader, header.fields(), SymbolIndexData.FieldRow.class);
            List<SymbolIndexData.MethodRow> methods = section(file, reader, header.methods(), SymbolIndexData.MethodRow.class);
            List<SymbolIndexData.ParameterRow> parameters = section(file, reader, header.parameters(), SymbolIndexData.ParameterRow.class);
            if (reader.next() != null) {
                throw new IOException("Symbol index has rows beyond its header counts: " + file);
            }
            return new SymbolIndexData(header, packages, types, fields, methods, parameters);
        }
    }

    private static void section(OutputStream output, List<?> rows) throws IOException {
        for (Object row : rows) {
            line(output, row);
        }
    }

    private static void line(OutputStream output, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        for (byte current : bytes) {
            if (current == '\r' || current == '\n') {
                throw new IOException("JSON mapper emitted non-compact JSON");
            }
        }
        output.write(bytes);
        output.write('\n');
    }

    private static SymbolIndexData.Header header(Path file, JsonlLineReader reader) throws IOException {
        byte[] line = reader.next();
        if (line == null) {
            throw new IOException("Symbol index is empty: " + file);
        }
        SymbolIndexData.Header header = parse(file, line, SymbolIndexData.Header.class);
        if (header.format() != SymbolIndexData.FORMAT) {
            throw new IOException("Unsupported symbol index format " + header.format() + ": " + file);
        }
        return header;
    }

    private static <T> List<T> section(Path file, JsonlLineReader reader, int count, Class<T> type) throws IOException {
        List<T> rows = new ArrayList<>(Math.min(count, INITIAL_SECTION_CAPACITY));
        for (int index = 0; index < count; index++) {
            byte[] line = reader.next();
            if (line == null) {
                throw new IOException("Symbol index is truncated: " + file);
            }
            rows.add(parse(file, line, type));
        }
        return rows;
    }

    private static <T> T parse(Path file, byte[] line, Class<T> type) throws IOException {
        T value;
        try {
            value = JSON.readValue(line, type);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Symbol index contains an invalid record: " + file, exception);
        }
        if (value == null) {
            throw new IOException("Symbol index contains an empty record: " + file);
        }
        return value;
    }
}
