package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.model.SourceNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;

class MemorySourceFileObjectTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sharesDecodedTextAcrossDeclarationMetadataAndWorkerFileObjects() {
        Path sourcePath = temporaryDirectory.resolve("Example.java").toAbsolutePath();
        String content = new String("package example; public class Example {}".toCharArray());
        SourceRoot root = new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), temporaryDirectory);
        DecodedSource discovered = new DecodedSource(root, sourcePath, Path.of("Example.java"), "Example.java", content, sourcePath.toUri(), "", List.of());
        DecodedSource attributed = discovered.withDeclarations("example", List.of("Example"));
        MemorySourceFileObject firstWorker = new MemorySourceFileObject(attributed);
        MemorySourceFileObject secondWorker = new MemorySourceFileObject(attributed);
        MemorySourceFileObject alias = new MemorySourceFileObject(attributed, "example.Alias");

        assertSame(content, attributed.content());
        assertSame(content, firstWorker.getCharContent(false));
        assertSame(content, secondWorker.getCharContent(false));
        assertSame(content, alias.getCharContent(false));
    }
}
