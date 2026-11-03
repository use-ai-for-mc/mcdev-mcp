package dev.mcdevmcp.packaging;

/**
 * The operating systems an MCPB bundle is built for. A bundle carries the application image
 * jpackage builds on that system, and its manifest can name the system but not the processor.
 */
enum McpbPlatform {
    WINDOWS("windows", "win32", "mcdev-mcp/mcdev-mcp.exe"),
    LINUX("linux", "linux", "mcdev-mcp/bin/mcdev-mcp"),
    MACOS("macos", "darwin", "mcdev-mcp.app/Contents/MacOS/mcdev-mcp");

    private final String id;
    private final String manifestName;
    private final String launcher;

    McpbPlatform(String id, String manifestName, String launcher) {
        this.id = id;
        this.manifestName = manifestName;
        this.launcher = launcher;
    }

    /**
     * The name the MCPB manifest schema gives this system.
     */
    String manifestName() {
        return manifestName;
    }

    /**
     * The native launcher, relative to the root of the bundle.
     */
    String launcher() {
        return launcher;
    }

    String manifestFileName() {
        return "manifest-" + id + ".json";
    }
}
