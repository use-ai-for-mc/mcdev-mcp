package dev.mcdevmcp.storage.migration;

import java.util.Set;

value record ParsedSourceUnit(String packageName, Set<String> declarations, boolean module) {
}
