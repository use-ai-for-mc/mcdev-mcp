package dev.mcdevmcp.tools.runtime;

sealed interface InWorldPollResult permits InWorldPollResult.Joined, InWorldPollResult.Failed, InWorldPollResult.Pending {
    record Joined() implements InWorldPollResult {
    }

    record Failed(String reason) implements InWorldPollResult {
    }

    record Pending() implements InWorldPollResult {
    }
}