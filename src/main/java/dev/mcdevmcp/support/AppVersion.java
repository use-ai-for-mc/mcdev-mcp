package dev.mcdevmcp.support;

import dev.mcdevmcp.app.Main;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

public final class AppVersion {
    public static final String TEST_FALLBACK_PROPERTY = "dev.mcdevmcp.test.versionFallback";
    private static final String ARTIFACT_NAME = "mcdev-mcp";

    private AppVersion() {
    }

    public static String current() {
        String manifestVersion = Main.class.getPackage().getImplementationVersion();
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return manifestVersion;
        }
        URL classResource = Main.class.getResource("Main.class");
        if (!Boolean.getBoolean(TEST_FALLBACK_PROPERTY) || classResource == null || !classResource.getProtocol().equals("file")) {
            throw new IllegalStateException("Missing implementation version in application manifest");
        }
        try (InputStream input = Main.class.getResourceAsStream("/version.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing version.properties");
            }
            var properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Missing application version");
            }
            return version;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read application version", exception);
        }
    }

    public static String executableJarName() {
        return ARTIFACT_NAME + "-" + current() + ".jar";
    }
}