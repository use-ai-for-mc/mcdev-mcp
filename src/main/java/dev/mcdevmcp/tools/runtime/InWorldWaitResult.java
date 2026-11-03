package dev.mcdevmcp.tools.runtime;

value record InWorldWaitResult(State state, String reason, double elapsedSeconds) {
    enum State {
        JOINED, FAILED, TIMEOUT
    }
}