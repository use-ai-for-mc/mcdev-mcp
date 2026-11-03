package dev.mcdevmcp.storage.migration;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface SourcePublicationHook {
    void at(String event, Path path) throws IOException;
}
