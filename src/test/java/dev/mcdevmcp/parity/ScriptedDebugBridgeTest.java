package dev.mcdevmcp.parity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("parity")
@Timeout(10)
class ScriptedDebugBridgeTest {
    @Test
    void closesASocketAcceptedAfterShutdownBegins(@TempDir Path temporaryDirectory) throws Exception {
        var accepted = new CountDownLatch(1);
        var releaseAcceptedSocket = new CountDownLatch(1);
        Runnable afterAccept = () -> {
            accepted.countDown();
            try {
                releaseAcceptedSocket.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding accepted test socket", exception);
            }
        };

        try (var bridge = ScriptedDebugBridge.start(temporaryDirectory, afterAccept);
             var client = new Socket(InetAddress.getLoopbackAddress(), bridge.port());
             var closer = Executors.newSingleThreadExecutor()) {
            client.setSoTimeout((int) Duration.ofSeconds(2).toMillis());
            assertTrue(accepted.await(2, TimeUnit.SECONDS), "Bridge did not accept the test socket");

            var close = closer.submit(bridge::close);
            try {
                assertTrue(awaitClosed(bridge), "Bridge shutdown did not begin");
            } finally {
                releaseAcceptedSocket.countDown();
            }

            close.get(2, TimeUnit.SECONDS);
            assertEquals(-1, client.getInputStream().read(), "Late accepted socket remained open after bridge shutdown");
        } finally {
            releaseAcceptedSocket.countDown();
        }
    }

    private static boolean awaitClosed(ScriptedDebugBridge bridge) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!bridge.isClosed() && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(10));
        }
        return bridge.isClosed();
    }
}
