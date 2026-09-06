package dev.mcdevmcp.tools.runtime;

sealed interface ClientExitResult permits ClientExitResult.Exited, ClientExitResult.Timeout {
    enum Phase {
        PORT, PROCESS
    }

    record Exited(boolean pidConfirmed) implements ClientExitResult {
    }

    record Timeout(Phase waitingOn) implements ClientExitResult {
    }
}