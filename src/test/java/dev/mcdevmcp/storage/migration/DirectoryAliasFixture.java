package dev.mcdevmcp.storage.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DirectoryAliasFixture {
    private DirectoryAliasFixture() {
    }

    public static void create(Path alias, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            String command = "New-Item -ItemType Junction -Path '" + quote(alias) + "' -Target '" + quote(target) + "' -ErrorAction Stop | Out-Null";
            try (Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command).redirectErrorStream(true).start()) {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Junction creation timed out");
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Junction creation failed: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        else {
            Files.createSymbolicLink(alias, target);
        }
    }

    private static String quote(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("'", "''");
    }

    public static void remove(Path alias) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(alias, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isSymbolicLink() && !attributes.isOther()) {
            throw new IOException("Refusing to remove a real directory as an alias: " + alias);
        }
        Files.delete(alias);
    }
}
