package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BlockingCalls;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionControlProcessIntegrationTest {
    private static SessionControlSupport.ListeningPidResolver realChild(AtomicReference<Process> captured, String mode) {
        return SessionControlSupport.listeningPidResolver(_ -> {
            List<String> childCommand = List.of(javaExecutable(), "--enable-preview", "-cp", System.getProperty("java.class.path"), SessionControlProcessFixture.class.getName(), mode);
            Process process = new ProcessBuilder(childCommand).start();
            captured.set(process);
            return process;
        });
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void assertNotNullProcess(Process process) {
        assertNotNull(process, "child process was never started");
    }

    @Test
    void drainsLargeStdoutAndStderrFromARealChild() throws Exception {
        AtomicReference<Process> captured = new AtomicReference<>();
        SessionControlSupport.ListeningPidResolver resolver = realChild(captured, "success");

        assertEquals(4242L, resolver.resolve(9876));
        Process process = captured.get();
        assertNotNullProcess(process);
        assertFalse(process.isAlive());
    }

    @Test
    void destroysARealHangingChildAtTheFourSecondDeadline() throws Exception {
        AtomicReference<Process> captured = new AtomicReference<>();
        SessionControlSupport.ListeningPidResolver resolver = realChild(captured, "hang");
        long started = System.nanoTime();

        assertNull(resolver.resolve(9876));
        assertTrue(System.nanoTime() - started >= Duration.ofSeconds(3).toNanos());
        Process process = captured.get();
        assertNotNullProcess(process);
        assertTrue(process.waitFor(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void cancellationDestroysARealRunningChildAndWaitsForOwnerCleanup() throws Exception {
        AtomicReference<Process> captured = new AtomicReference<>();
        SessionControlSupport.ListeningPidResolver resolver = realChild(captured, "hang");
        var result = BlockingCalls.start(() -> resolver.resolve(9876));
        BlockingCalls.awaitUntil(() -> captured.get() != null);
        Process process = captured.get();
        assertNotNullProcess(process);
        assertTrue(result.cancel());
        assertTrue(process.waitFor(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        result.awaitStopped();
    }
}