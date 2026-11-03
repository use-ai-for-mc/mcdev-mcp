package dev.mcdevmcp.storage.migration;

import java.nio.file.Path;
import java.util.Objects;

public value record SourceInventoryEntry(String relativePath, SourceEntryKind kind, long size, String sha256) {
    public SourceInventoryEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        for (int index = 0; index < relativePath.length(); index++) {
            char unit = relativePath.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= relativePath.length() || !Character.isLowSurrogate(relativePath.charAt(index))) {
                    throw new IllegalArgumentException("Inventory path contains an unpaired surrogate");
                }
            }
            else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("Inventory path contains an unpaired surrogate");
            }
        }
        if (relativePath.indexOf('\\') >= 0 || relativePath.indexOf(':') >= 0 || relativePath.startsWith("/") || (!relativePath.isEmpty() && !Path.of(relativePath).normalize().toString().replace('\\', '/').equals(relativePath))) {
            throw new IllegalArgumentException("Unsafe inventory path: " + relativePath);
        }
        for (String part : relativePath.split("/", -1)) {
            if (part.equals("..") || part.equals(".")) throw new IllegalArgumentException("Unsafe inventory path");
        }
        if (size < 0 || (kind == SourceEntryKind.DIRECTORY && (size != 0 || !sha256.isEmpty()))) {
            throw new IllegalArgumentException("Invalid directory or size inventory");
        }
        if (kind == SourceEntryKind.FILE && (sha256.length() != 64 || !sha256.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("Invalid file hash");
        }
    }
}