package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonAbsenceTest {
    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));

    @Test
    void gsonIsAbsentFromTheTestRuntimeAndShadedJar() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.google.gson.Gson"));

        try (var jar = new JarFile(JAR.toFile())) {
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("com/google/gson/")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/sqlite/") || entry.getName().startsWith("org/sqlite/native/")));
        }

        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.sqlite.JDBC"));
    }
}
