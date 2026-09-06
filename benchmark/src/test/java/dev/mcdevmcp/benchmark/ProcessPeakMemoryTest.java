package dev.mcdevmcp.benchmark;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessPeakMemoryTest {
    @Test
    void linuxRequiresSinglePositiveKilobyteHighWaterMark() throws Exception {
        assertEquals(123 * 1024L, ProcessPeakMemory.linuxPeakBytes(List.of("Name: java", "VmHWM:\t123 kB")));
        for (List<String> invalid : List.of(List.<String>of(), List.of("VmHWM: 0 kB"), List.of("VmHWM: -1 kB"), List.of("VmHWM: 1 MB"), List.of("VmHWM: 1"), List.of("VmHWM: 9223372036854775807 kB"), List.of("VmHWM: 1 kB", "VmHWM: 2 kB"))) {
            assertThrows(IOException.class, () -> ProcessPeakMemory.linuxPeakBytes(invalid), invalid.toString());
        }
    }

    @Test
    void windowsAcceptsOnlyOnePositiveInteger() throws Exception {
        assertEquals(12345L, ProcessPeakMemory.positiveBytes("12345\r\n"));
        for (String invalid : List.of("", "0", "-1", "+1", "1 2", "1\n2", "1.5", "1,000", "warning\n1", "9223372036854775808")) {
            assertThrows(IOException.class, () -> ProcessPeakMemory.positiveBytes(invalid), invalid);
        }
    }

    @Test
    void commandTargetsJavaPidWithoutProfileOrVisibleWindow() {
        List<String> command = ProcessPeakMemory.windowsCommand(Path.of("system-powershell.exe"), 987654321L);
        assertEquals(List.of("system-powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command"), command.subList(0, 7));
        assertTrue(command.getLast().contains("GetProcessById(987654321)"));
        assertFalse(command.getLast().contains("$PID"));
        assertTrue(command.getLast().contains("PeakWorkingSet64"));
        assertThrows(IllegalArgumentException.class, () -> ProcessPeakMemory.windowsCommand(Path.of("unused"), 0));
    }

    @Test
    void platformAttributionRoundTripsInRuntimeMetadata() throws Exception {
        assertEquals(ProcessMemoryMetric.LINUX_VM_HWM, ProcessPeakMemory.metric("Linux"));
        assertEquals(ProcessMemoryMetric.WINDOWS_PEAK_WORKING_SET, ProcessPeakMemory.metric("Windows 11"));
        assertEquals(ProcessMemoryMetric.UNAVAILABLE, ProcessPeakMemory.metric("Mac OS X"));
        BenchmarkRuntimeMetadata runtime = BenchmarkRuntimeMetadata.current();
        byte[] json = McpJsonDefaults.getMapper().writeValueAsBytes(runtime);
        assertEquals(runtime, McpJsonDefaults.getMapper().readValue(json, BenchmarkRuntimeMetadata.class));
        assertEquals(System.getProperty("os.name"), runtime.osName());
        assertEquals(ProcessPeakMemory.metric(), runtime.memoryMetric());
    }

    @Test
    void queryRejectsFailureExcessOutputAndTimeout() throws Exception {
        assertEquals("1234", ProcessPeakMemory.query(probe("valid"), Duration.ofSeconds(10)));
        assertThrows(IOException.class, () -> ProcessPeakMemory.query(probe("failure"), Duration.ofSeconds(10)));
        assertThrows(IOException.class, () -> ProcessPeakMemory.query(probe("large"), Duration.ofSeconds(10)));
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(IOException.class, () -> ProcessPeakMemory.query(probe("wait"), Duration.ofMillis(500))));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void realWindowsMeasurementObservesCurrentJavaProcess() throws Exception {
        assertEquals(ProcessMemoryMetric.WINDOWS_PEAK_WORKING_SET, ProcessPeakMemory.metric());
        long before = ProcessPeakMemory.currentPeakBytes();
        byte[] touched = new byte[32 * 1024 * 1024];
        for (int i = 0; i < touched.length; i += 4096) touched[i] = 1;
        long after = ProcessPeakMemory.currentPeakBytes();
        assertTrue(before > 0);
        assertTrue(after >= before, "Process-lifetime peak cannot decrease");
        assertEquals(1, touched[touched.length - 4096]);
        assertTrue(AnalysisBenchmarkMain.peakRssBytes() > 0);
        assertTrue(CorpusQualificationMain.peakRssBytes() > 0);
    }

    private static List<String> probe(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classes = Path.of(QueryProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp", classes, QueryProbe.class.getName(), mode);
    }

    public static final class QueryProbe {
        static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "valid" -> System.out.print("1234");
                case "failure" -> System.exit(5);
                case "large" -> System.out.print("1".repeat(200));
                case "wait" -> Thread.sleep(30000);
                default -> throw new IllegalArgumentException("Unknown probe");
            }
        }
    }
}
