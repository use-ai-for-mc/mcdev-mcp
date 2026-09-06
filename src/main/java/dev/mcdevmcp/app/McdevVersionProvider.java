package dev.mcdevmcp.app;

import dev.mcdevmcp.support.AppVersion;
import picocli.CommandLine.IVersionProvider;

public final class McdevVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[]{AppVersion.current()};
    }
}