package dev.mcdevmcp.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Executable child used to prove benchmark process boundaries.
 */
public final class BenchmarkChildProcessFixtureMain {
    private BenchmarkChildProcessFixtureMain() {
    }

    static void main(String[] arguments) throws Exception {
        String command = arguments.length == 0 ? "success" : arguments[0];
        switch (command) {
            case "success" -> System.out.println("fixture-ok");
            case "failure" -> {
                System.err.println("fixture-failure");
                System.exit(17);
            }
            case "overflow" -> {
                byte[] output = new byte[AnalysisBenchmarkMain.MAXIMUM_CHILD_OUTPUT_BYTES + 1];
                Arrays.fill(output, (byte) 'x');
                System.out.write(output);
            }
            case "sleep" -> Thread.sleep(5_000L);
            default ->
                    throw new IllegalArgumentException("Unknown fixture command: " + new String(command.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        }
    }
}
