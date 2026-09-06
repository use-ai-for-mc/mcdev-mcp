package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

final class SourceCorpus {
    private final List<DecodedSource> sources;
    private final Map<URI, DecodedSource> byUri;

    SourceCorpus(List<DecodedSource> sources) {
        List<DecodedSource> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.naturalOrder());
        this.sources = List.copyOf(sorted);
        Map<URI, DecodedSource> indexed = new HashMap<>();
        for (DecodedSource source : sorted) {
            DecodedSource previous = indexed.put(source.uri(), source);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate in-memory source URI: " + source.uri());
            }
        }
        byUri = Map.copyOf(indexed);
    }

    static SourceCorpus discover(List<SourceRoot> roots, Cancellation cancellation) throws IOException, InterruptedException {
        List<DecodedSource> discovered = new ArrayList<>();
        int rootOrdinal = 0;
        for (SourceRoot root : roots) {
            cancellation.throwIfCancelled();
            if (!Files.isDirectory(root.path(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Source root is not a directory: " + root.path());
            }
            try (var paths = Files.walk(root.path())) {
                List<Path> javaSources = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).filter(path -> path.getFileName().toString().endsWith(".java")).sorted(Comparator.comparing(path -> new PortablePath(root.path().relativize(path)))).toList();
                for (Path source : javaSources) {
                    cancellation.throwIfCancelled();
                    Path relative = root.path().relativize(source).normalize();
                    String relativeName = new PortablePath(relative).value();
                    String content = decode(source);
                    URI uri = sourceUri(rootOrdinal, relativeName);
                    discovered.add(new DecodedSource(root, source, relative, relativeName, content, uri, "", List.of()));
                }
            }
            rootOrdinal++;
        }
        return new SourceCorpus(discovered);
    }

    private static String decode(Path source) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(source))).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Source is not strict UTF-8: " + source, exception);
        }
    }

    private static URI sourceUri(int rootOrdinal, String relativeName) {
        try {
            return new URI("memory", null, "/" + rootOrdinal + "/" + relativeName, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Unable to create source URI for " + relativeName, exception);
        }
    }

    List<DecodedSource> sources() {
        return sources;
    }

    DecodedSource require(URI uri) {
        DecodedSource source = byUri.get(uri);
        if (source == null) {
            throw new IllegalArgumentException("Unknown in-memory source URI: " + uri);
        }
        return source;
    }
}