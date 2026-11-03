package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.OutputStream;
import java.nio.file.Path;

final class VersionOperationLeaseProcessMain {
    private VersionOperationLeaseProcessMain() {
    }

    static void main(String[] arguments) throws Exception {
        PlatformPaths paths = new PlatformPaths(Path.of(arguments[0]));
        MinecraftVersion version = new MinecraftVersion(arguments[1]);
        try (var lease = VersionOperationLease.read(paths, version)) {
            lease.require(paths, version);
            System.out.println("read-held");
            System.out.flush();
            // Parent closes stdin to release the operation lease.
            System.in.transferTo(OutputStream.nullOutputStream());
        }
    }
}
