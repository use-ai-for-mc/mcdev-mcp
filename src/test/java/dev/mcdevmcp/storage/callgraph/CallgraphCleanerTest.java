package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleLock;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CallgraphCleanerTest {
    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    @Test
    void removesPublishedBundleAndIsIdempotent() throws Exception {
        Path bundle = Files.createTempDirectory("callgraph-cleaner").resolve("bundle");
        Files.createDirectories(bundle.resolve("generations/one"));
        Files.writeString(bundle.resolve("current.json"), "{}");
        Files.writeString(bundle.resolve("generations/one/calls.jsonl"), "{}\n");
        CallgraphCleaner cleaner = new CallgraphCleaner();
        cleaner.clean(bundle);
        cleaner.clean(bundle);
        assertTrue(Files.isDirectory(bundle));
        assertTrue(Files.isRegularFile(bundle.resolve("publication.lock")));
        assertFalse(Files.exists(bundle.resolve("current.json")));
        assertFalse(Files.exists(bundle.resolve("generations")));
        assertEquals(CallgraphRepository.PublicationStatus.ABSENT, CallgraphRepository.publicationStatus(bundle));
    }

    @Test
    void doesNotDeleteWhileAReaderHoldsTheBundleLock() throws Exception {
        Path bundle = Files.createTempDirectory("callgraph-cleaner-locked").resolve("bundle");
        Files.createDirectories(bundle);
        Path pointer = bundle.resolve("current.json");
        Files.writeString(pointer, "{}");

        try (BundleLock lock = BundleLock.read(bundle, Duration.ofSeconds(1))) {
            assertNotNull(lock);
            assertThrows(IOException.class, () -> new CallgraphCleaner(Duration.ZERO).clean(bundle));
        }

        assertTrue(Files.exists(pointer));
    }

    @Test
    void rejectsLinksBeforeDeletingAnyBundleState() throws Exception {
        Path bundle = Files.createTempDirectory("callgraph-cleaner-linked").resolve("bundle");
        Path outside = Files.createTempDirectory("callgraph-cleaner-outside");
        Path sentinel = outside.resolve("sentinel.txt");
        Files.createDirectories(bundle.resolve("generations/one"));
        Files.writeString(bundle.resolve("current.json"), "{}");
        Files.writeString(sentinel, "keep");
        createSymbolicLinkOrSkip(bundle.resolve("generations/one/linked"), outside);

        assertThrows(IOException.class, () -> new CallgraphCleaner().clean(bundle));
        assertEquals("keep", Files.readString(sentinel));
        assertTrue(Files.exists(bundle.resolve("current.json")));
    }

    @Test
    void rejectsASymbolicPublicationLockWithoutOpeningItsTarget() throws Exception {
        Path bundle = Files.createTempDirectory("callgraph-cleaner-linked-lock").resolve("bundle");
        Path outsideLock = Files.createTempFile("callgraph-cleaner-outside-lock", ".lock");
        Files.createDirectories(bundle);
        Files.writeString(outsideLock, "keep");
        createSymbolicLinkOrSkip(bundle.resolve("publication.lock"), outsideLock);

        assertThrows(IOException.class, () -> new CallgraphCleaner().clean(bundle));
        assertEquals("keep", Files.readString(outsideLock));
    }

    @Test
    void distinguishesAbsentPublishedAndCorruptPointers() throws Exception {
        Path root = Files.createTempDirectory("callgraph-publication-status");
        Path absent = root.resolve("absent");
        Path published = root.resolve("published");
        Path corrupt = root.resolve("corrupt");

        assertEquals(CallgraphRepository.PublicationStatus.ABSENT, CallgraphRepository.publicationStatus(absent));
        assertFalse(Files.exists(absent));

        CallgraphBundleTestSupport.publish(published, new MinecraftVersion("1.21.11"), List.of());
        assertEquals(CallgraphRepository.PublicationStatus.PUBLISHED, CallgraphRepository.publicationStatus(published));
        assertTrue(CallgraphRepository.isPublished(published));

        Files.createDirectories(corrupt.resolve("current.json"));
        assertEquals(CallgraphRepository.PublicationStatus.CORRUPT, CallgraphRepository.publicationStatus(corrupt));
        assertTrue(CallgraphRepository.isPublished(corrupt));
    }
}
