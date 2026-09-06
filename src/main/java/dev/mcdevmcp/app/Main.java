package dev.mcdevmcp.app;

import picocli.CommandLine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class Main {
    public static int execute(String[] arguments, int javaFeature, PrintWriter output, PrintWriter error) {
        if (javaFeature < 26) {
            return rejectOldJava(javaFeature, error);
        }
        return execute(arguments, javaFeature, output, error, CommandContext.production());
    }

    // Allows command tests to use deterministic paths and collaborators.
    public static int execute(String[] arguments, int javaFeature, PrintWriter output, PrintWriter error, CommandContext context) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(context, "context");
        if (javaFeature < 26) {
            return rejectOldJava(javaFeature, error);
        }

        CliHelp.Preflight preflight = CliHelp.preflight(arguments);
        if (preflight != null) {
            output.print(platformLines(preflight.stdout()));
            error.print(platformLines(preflight.stderr()));
            output.flush();
            error.flush();
            return preflight.exitCode();
        }

        var commandLine = new CommandLine(new McdevCommand(), context);
        commandLine.setOut(output);
        commandLine.setErr(error);
        commandLine.setParameterExceptionHandler((exception, _) -> report(exception.getCommandLine(), exception.getMessage(), 1));
        commandLine.setExecutionExceptionHandler((exception, command, _) -> report(command, conciseMessage(exception), command.getCommandSpec().exitCodeOnExecutionException()));
        return commandLine.execute(arguments);
    }

    private static String conciseMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String platformLines(String text) {
        return text.replace("\n", System.lineSeparator());
    }

    @SuppressWarnings("SameReturnValue")
    private static int rejectOldJava(int javaFeature, PrintWriter error) {
        Objects.requireNonNull(error, "error");
        error.printf("Java 26 or newer is required; detected Java %d.%n", javaFeature);
        error.flush();
        return 1;
    }

    private static int report(CommandLine commandLine, String message, int exitCode) {
        commandLine.getErr().println(message);
        commandLine.getErr().flush();
        return exitCode;
    }

    void main(String[] arguments) {
        var output = new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
        var error = new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.err), StandardCharsets.UTF_8), true);
        System.exit(execute(arguments, Runtime.version().feature(), output, error));
    }
}
