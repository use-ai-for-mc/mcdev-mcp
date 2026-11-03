package dev.mcdevmcp.tools.runtime;

import java.nio.charset.StandardCharsets;

/**
 * Small real child used to verify PID-probe ownership and pipe draining.
 */
final class SessionControlProcessFixture {
    private static final int STDERR_BYTES = 256 * 1024;
    private static final int STDOUT_BYTES = 256 * 1024;

    private SessionControlProcessFixture() {
    }

    static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "success" -> success();
            case "hang" -> Thread.sleep(Long.MAX_VALUE);
            default -> throw new IllegalArgumentException(arguments[0]);
        }
    }

    private static void success() throws InterruptedException {
        Thread errorWriter = Thread.ofPlatform().start(() -> {
            byte[] block = "stderr-noise\n".getBytes(StandardCharsets.UTF_8);
            for (int written = 0; written < STDERR_BYTES; written += block.length) {
                System.err.write(block, 0, Math.min(block.length, STDERR_BYTES - written));
            }
            System.err.flush();
        });
        byte[] pidBlock = "4242\n".getBytes(StandardCharsets.UTF_8);
        for (int written = 0; written < STDOUT_BYTES; written += pidBlock.length) {
            System.out.write(pidBlock, 0, Math.min(pidBlock.length, STDOUT_BYTES - written));
        }
        System.out.flush();
        errorWriter.join();
    }
}