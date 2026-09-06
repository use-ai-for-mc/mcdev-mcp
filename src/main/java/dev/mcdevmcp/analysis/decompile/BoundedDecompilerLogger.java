package dev.mcdevmcp.analysis.decompile;

import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

final class BoundedDecompilerLogger extends IFernflowerLogger {
    private static final int MAXIMUM_MESSAGES = 32;
    private static final int MAXIMUM_MESSAGE_LENGTH = 400;
    private final ArrayDeque<String> messages = new ArrayDeque<>();
    private final Runnable cancellationCheckpoint;

    BoundedDecompilerLogger() {
        this(() -> {
        });
    }

    BoundedDecompilerLogger(Runnable cancellationCheckpoint) {
        this.cancellationCheckpoint = Objects.requireNonNull(cancellationCheckpoint, "cancellationCheckpoint");
    }

    @Override
    public synchronized void writeMessage(String message, Severity severity) {
        checkpoint();
        if (severity.ordinal() >= Severity.WARN.ordinal()) {
            add(severity + ": " + message);
        }
    }

    @Override
    public void writeMessage(String message, Severity severity, Throwable throwable) {
        writeMessage(message + (throwable == null ? "" : ": " + throwable.getClass().getSimpleName()), severity);
    }

    @Override
    public void startProcessingClass(String className) {
        checkpoint();
    }

    @Override
    public void endProcessingClass() {
        checkpoint();
    }

    @Override
    public void startReadingClass(String className) {
        checkpoint();
    }

    @Override
    public void endReadingClass() {
        checkpoint();
    }

    @Override
    public void startClass(String className) {
        checkpoint();
    }

    @Override
    public void endClass() {
        checkpoint();
    }

    @Override
    public void startMethod(String methodName) {
        checkpoint();
    }

    @Override
    public void endMethod() {
        checkpoint();
    }

    @Override
    public void startWriteClass(String className) {
        checkpoint();
    }

    @Override
    public void endWriteClass() {
        checkpoint();
    }

    synchronized List<String> messages() {
        return List.copyOf(messages);
    }

    private void add(String message) {
        if (messages.size() == MAXIMUM_MESSAGES) {
            messages.removeFirst();
        }
        messages.addLast(message.length() <= MAXIMUM_MESSAGE_LENGTH ? message : message.substring(0, MAXIMUM_MESSAGE_LENGTH));
    }

    private void checkpoint() {
        cancellationCheckpoint.run();
    }
}
