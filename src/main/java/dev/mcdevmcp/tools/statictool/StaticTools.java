package dev.mcdevmcp.tools.statictool;

final class StaticTools {
    private StaticTools() {
    }

    static String truncationNote(int shown, boolean truncated, NormalizedLimit limit, String noun) {
        return truncationNote(shown, shown, truncated, limit, noun);
    }

    static String truncationNote(int shown, int total, boolean truncated, NormalizedLimit limit, String noun) {
        if (!truncated) {
            return "\nTotal: " + total + " " + noun;
        }
        String capped = limit.capped() ? " (limit was capped to " + limit.value() + " by the server)" : "";
        String more = total > shown ? (total - shown) + "+ more" : "possibly more";
        return "\n... and " + more + " " + noun + " (showing first " + shown + "; pass a larger `limit` to see more)" + capped;
    }
}
