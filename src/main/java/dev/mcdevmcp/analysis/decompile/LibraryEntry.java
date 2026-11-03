package dev.mcdevmcp.analysis.decompile;

import java.util.Objects;

public value record LibraryEntry(LibraryDownloads downloads, String name) {
    public LibraryEntry {
        Objects.requireNonNull(name, "name");
    }
}