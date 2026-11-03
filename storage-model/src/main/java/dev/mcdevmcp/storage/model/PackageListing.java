package dev.mcdevmcp.storage.model;

import java.util.List;

/**
 * A page of indexed package names together with the complete package count.
 */
public value record PackageListing(List<String> packages, int total) {
    public PackageListing {
        packages = List.copyOf(packages);
        if (total < packages.size()) {
            throw new IllegalArgumentException("total is smaller than page size");
        }
    }
}