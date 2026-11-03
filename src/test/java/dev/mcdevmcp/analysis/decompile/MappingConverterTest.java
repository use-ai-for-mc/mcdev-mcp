package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class MappingConverterTest {
    private static long temporaryFiles(Path directory, String targetName) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(targetName + ".") && name.endsWith(".tmp");
            }).count();
        }
    }

    @Test
    void convertsClassFieldAndOverloadedMethodsToTinyV2() throws Exception {
        Path root = Files.createTempDirectory("mapping-converter");
        Path input = root.resolve("client.txt");
        Path output = root.resolve("client.tiny");
        Files.writeString(input, """
                                 example.Named -> a:
                                     int field -> b
                                     void method(int) -> c
                                     void method(java.lang.String) -> d
                                 """);
        List<Integer> progress = new ArrayList<>();
        new MappingConverter().convert(input, output, (_, percent, _) -> progress.add(percent), Cancellation.none());
        String tiny = Files.readString(output);
        assertTrue(tiny.startsWith("tiny\t2\t0\tofficial\tnamed"));
        assertTrue(tiny.contains("c\ta\texample/Named"));
        assertTrue(tiny.contains("\tf\tI\tb\tfield"));
        assertTrue(tiny.contains("\tm\t(I)V\tc\tmethod"));
        assertTrue(tiny.contains("\tm\t(Ljava/lang/String;)V\td\tmethod"));
        assertEquals(0, progress.getFirst());
        assertEquals(100, progress.getLast());

        Path secondOutput = root.resolve("second.tiny");
        new MappingConverter().convert(input, secondOutput);
        assertArrayEquals(Files.readAllBytes(output), Files.readAllBytes(secondOutput));
    }

    @Test
    void preservesPriorOutputWhenInputIsMalformed() throws Exception {
        Path root = Files.createTempDirectory("mapping-converter");
        Path input = root.resolve("client.txt");
        Path output = root.resolve("client.tiny");
        Files.writeString(output, "previous");
        Files.writeString(input, "not a mapping");
        assertThrows(IOException.class, () -> new MappingConverter().convert(input, output));
        assertEquals("previous", Files.readString(output));
        assertEquals(0, temporaryFiles(root, output.getFileName().toString()));
    }

    @Test
    void cancellationPreservesPriorOutputInterruptsCallerAndDeletesTemporaryFile() throws Exception {
        Path root = Files.createTempDirectory("mapping-converter-cancel");
        Path input = root.resolve("client.txt");
        Path output = root.resolve("client.tiny");
        StringBuilder mappings = new StringBuilder();
        for (int index = 0; index < 4_000; index++) {
            mappings.append("example.Named").append(index).append(" -> a").append(index).append(":\n");
        }
        Files.writeString(input, mappings);
        Files.writeString(output, "previous");
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            assertThrows(IOException.class, () -> new MappingConverter().convert(input, output, (_, percent, _) -> {
                if (percent > 0) {
                    cancelled.set(true);
                }
            }, cancelled::get));
            assertTrue(Thread.interrupted(), "cancellation must preserve the caller interrupt signal");
        } finally {
            assertFalse(Thread.interrupted(), "test must not leak interruption");
        }
        assertEquals("previous", Files.readString(output));
        assertEquals(0, temporaryFiles(root, output.getFileName().toString()));
    }
}
