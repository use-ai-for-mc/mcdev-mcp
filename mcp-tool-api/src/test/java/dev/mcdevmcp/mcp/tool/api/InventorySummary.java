package dev.mcdevmcp.mcp.tool.api;

import java.util.List;

record InventorySummary(int slots, List<InventoryItem> items) {
}