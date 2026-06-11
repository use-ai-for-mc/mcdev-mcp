import { scriptLogger } from "./script-logger.js";

export const mcScriptLogsTool = {
    name: "mc_script_logs",
    description: `View script execution logs and error statistics. Useful for debugging common
Groovy scripting issues and identifying patterns in failed scripts.

Modes:
- "errors": Show recent error entries (default, last 20)
- "stats": Show error statistics grouped by error type
- "paths": Show log file paths for manual inspection

The logs are stored at the paths returned by mode="paths". You can use the Read tool
to inspect them directly. Format is JSON Lines (.jsonl).`,
    inputSchema: {
        type: "object" as const,
        properties: {
            mode: {
                type: "string",
                enum: ["errors", "stats", "paths"],
                description: "What to show: 'errors' (recent failures), 'stats' (error patterns), 'paths' (file locations)",
            },
            limit: {
                type: "number",
                description: "Number of entries to show (for 'errors' mode). Default: 20",
            },
        },
        required: [],
    },

    handler: async (args: { mode?: string; limit?: number }) => {
        const mode = args.mode || "errors";

        if (mode === "paths") {
            const text = `Script log files:
  All executions: ${scriptLogger.getAllLogPath()}
  Errors only:    ${scriptLogger.getErrorsLogPath()}
  Log directory:  ${scriptLogger.getLogDir()}

Use the Read tool to view these files. Format: JSON Lines (one JSON object per line).`;
            return { content: [{ type: "text" as const, text }] };
        }

        if (mode === "stats") {
            const stats = scriptLogger.getErrorStats();
            if (stats.length === 0) {
                return { content: [{ type: "text" as const, text: "No errors logged yet." }] };
            }

            let text = `Error Statistics (${stats.length} distinct error types):\n\n`;
            for (const stat of stats.slice(0, 15)) {
                text += `## ${stat.error}\n`;
                text += `   Count: ${stat.count} | Last seen: ${stat.lastSeen}\n`;
                text += `   Example script:\n`;
                text += `   \`\`\`groovy\n   ${stat.examples[0].split('\n').join('\n   ')}\n   \`\`\`\n\n`;
            }

            if (stats.length > 15) {
                text += `... and ${stats.length - 15} more error types\n`;
            }

            return { content: [{ type: "text" as const, text }] };
        }

        // Default: show recent errors
        const limit = args.limit || 20;
        const errors = scriptLogger.getRecentErrors(limit);

        if (errors.length === 0) {
            return { content: [{ type: "text" as const, text: "No errors logged yet." }] };
        }

        let text = `Recent Script Errors (${errors.length} entries):\n\n`;
        for (const entry of errors.slice(-limit).reverse()) {
            text += `---\n`;
            text += `**${entry.timestamp}** (${entry.duration_ms}ms)\n`;
            text += `Error: ${entry.error}\n`;
            text += `\`\`\`groovy\n${entry.code}\n\`\`\`\n\n`;
        }

        return { content: [{ type: "text" as const, text }] };
    }
};
