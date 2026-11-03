package dev.mcdevmcp.tools.runtime;

sealed interface InWorldPollResult permits InWorldPollResult.Joined, InWorldPollResult.Failed, InWorldPollResult.Pending {
    value record Joined() implements InWorldPollResult {
    }

    value record Failed(String reason) implements InWorldPollResult {
    }

    value record Pending() implements InWorldPollResult {
    }
}