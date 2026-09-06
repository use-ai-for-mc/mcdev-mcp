package dev.mcdevmcp.tools.runtime;

record InWorldWaitResult(State state, String reason, double elapsedSeconds) {
    enum State {
        JOINED, FAILED, TIMEOUT
    }
}