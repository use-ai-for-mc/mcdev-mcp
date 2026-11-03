package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SourceTreeInventoryTest {
    @TempDir
    Path temporary;

    private static void assertGolden(Path root, String frameHex, String expectedHash) throws Exception {
        // Literal framing bytes and hashes were independently evaluated using .NET SHA256.HashData.
        byte[] frame = HexFormat.of().parseHex(frameHex);
        assertEquals(expectedHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame)));
        assertEquals(expectedHash, SourceTreeInventory.capture(root, Cancellation.none()).sha256());
    }

    @Test
    void coversEmptyDirectoriesMarkersAndMissingRoot() throws Exception {
        Path root = temporary.resolve("source");
        SourceTreeInventory missing = SourceTreeInventory.capture(root, Cancellation.none());
        assertFalse(missing.present());
        Files.createDirectories(root.resolve("empty"));
        Files.writeString(root.resolve("A.java"), "class A {}");
        Files.writeString(root.resolve("marker"), "user bytes");
        SourceTreeInventory original = SourceTreeInventory.capture(root, Cancellation.none());
        assertEquals(List.of("", "A.java", "empty", "marker"), original.entries().stream().map(SourceInventoryEntry::relativePath).toList());
        assertEquals(original, SourceTreeInventory.capture(root, Cancellation.none()));
        Files.createDirectory(root.resolve("empty/new"));
        assertNotEquals(original, SourceTreeInventory.capture(root, Cancellation.none()));
        Files.delete(root.resolve("empty/new"));
        Files.writeString(root.resolve("marker"), "changed");
        assertNotEquals(original, SourceTreeInventory.capture(root, Cancellation.none()));
    }

    @Test
    void checksCancellationAndRejectsForgedInventory() {
        assertThrows(IOException.class, () -> SourceTreeInventory.capture(temporary, () -> true));
        assertThrows(IllegalArgumentException.class, () -> new SourceInventoryEntry("../outside", SourceEntryKind.FILE, 0, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new SourceTreeInventory(false, List.of(), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new SourceInventoryEntry("\uD800", SourceEntryKind.DIRECTORY, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new SourceInventoryEntry("\uDC00", SourceEntryKind.DIRECTORY, 0, ""));
    }

    @Test
    void missingRootHasMagicOnlyGoldenFrame() throws Exception {
        assertGolden(temporary.resolve("missing"), "6d636465762d736f757263652d696e76656e746f72792d7631", "42e3d2828fbdc463b3772feb9209cf1165ca86caf9023166be6a25e834867796");
    }

    @Test
    void emptyDirectoryHasExplicitRootGoldenFrame() throws Exception {
        assertGolden(temporary, "6d636465762d736f757263652d696e76656e746f72792d76314400000000", "4e5f4d2189046ff83e7e9bd6b6a03107ee2b52b3636cb10ea6c8b223093fb735");
    }

    @Test
    void fileGoldenFrameUsesRawDigestAndUnsignedLength() throws Exception {
        Path file = Files.writeString(temporary.resolve("file"), "abc");
        assertGolden(file, "6d636465762d736f757263652d696e76656e746f72792d763146000000000000000000000003ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "46ac128ae96013fa97dc3a5e77e27ddbef656e330db09fdf5007212ea95c3533");
    }

    @Test
    void unicodeGoldenFrameUsesUtf8LengthsAndUtf16PathOrdering() throws Exception {
        Files.createDirectory(temporary.resolve("\uE000"));
        Files.createDirectory(temporary.resolve("\uD800\uDC00"));
        assertGolden(temporary, "6d636465762d736f757263652d696e76656e746f72792d763144000000004400000004f09080804400000003ee8080", "2581b152732f5fc2dab6fc2ec73df683603b5047e8f721992f5a2728208aa529");
        assertEquals(List.of("", "\uD800\uDC00", "\uE000"), SourceTreeInventory.capture(temporary, Cancellation.none()).entries().stream().map(SourceInventoryEntry::relativePath).toList());
    }

    @Test
    void markerAndEmptyDirectoryGoldenFrameExcludesNothingByFilename() throws Exception {
        Files.createDirectory(temporary.resolve("empty"));
        Files.writeString(temporary.resolve("source-preparation.json"), "abc");
        assertGolden(temporary, "6d636465762d736f757263652d696e76656e746f72792d763144000000004400000005656d7074794600000017736f757263652d7072657061726174696f6e2e6a736f6e0000000000000003ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "30e78082687cb67626009125ae119d3748f4b20fc6efaf8e877a85ceb239cd9f");
    }

    @Test
    void refusesActualDirectoryLinkAndPreservesDestination() throws Exception {
        Path destination = Files.createDirectory(temporary.resolve("destination"));
        Path sentinel = Files.writeString(destination.resolve("sentinel.txt"), "destination bytes");
        Path tree = Files.createDirectory(temporary.resolve("tree"));
        Path link = tree.resolve("link");
        try {
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
                try (Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), destination.toString()).redirectErrorStream(true).start()) {
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        fail("Junction fixture creation timed out");
                    }
                    assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            else {
                Files.createSymbolicLink(link, destination);
            }
            assertThrows(IOException.class, () -> SourceTreeInventory.capture(tree, Cancellation.none()));
            assertThrows(IOException.class, () -> SourceTreeInventory.capture(link.resolve("sentinel.txt"), Cancellation.none()));
            assertEquals("destination bytes", Files.readString(sentinel));
        } finally {
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link);
        }
        assertEquals("destination bytes", Files.readString(sentinel));
    }
}