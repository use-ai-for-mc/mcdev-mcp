package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConformanceDependencyIsolationTest {
    private static final List<String> CONFORMANCE_ARCHIVE_PREFIXES = List.of("dev/mcdevmcp/conformance/", "jakarta/servlet/", "org/apache/catalina/", "org/apache/coyote/", "org/apache/tomcat/");

    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));

    @Test
    void tomcatConformanceDependenciesAreAbsentFromProductionRuntimeAndShadedJar() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.catalina.startup.Tomcat"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.coyote.ProtocolHandler"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.tomcat.util.http.parser.HttpParser"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("jakarta.servlet.http.HttpServlet"));

        try (var jar = new JarFile(JAR.toFile())) {
            for (String prefix : CONFORMANCE_ARCHIVE_PREFIXES) {
                assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith(prefix)), () -> "Conformance-only archive entry leaked into production JAR: " + prefix);
            }
        }
    }
}
