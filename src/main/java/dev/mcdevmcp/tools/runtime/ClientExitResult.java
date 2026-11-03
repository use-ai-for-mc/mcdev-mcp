package dev.mcdevmcp.tools.runtime;

sealed interface ClientExitResult permits ClientExitResult.Exited, ClientExitResult.Timeout {
    enum Phase {
        PORT, PROCESS
    }

    value record Exited(boolean pidConfirmed) implements ClientExitResult {
    }

    value record Timeout(Phase waitingOn) implements ClientExitResult {
    }
}