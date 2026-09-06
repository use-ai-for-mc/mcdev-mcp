package dev.mcdevmcp.analysis.callgraph;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class CallgraphScanner {
    static final int MAXIMUM_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BATCH_WINDOW = 256;
    private static final long MAXIMUM_IN_FLIGHT_CLASS_BYTES = 64L * 1024 * 1024;
    private final CallgraphWriter writer;

    public CallgraphScanner() {
        this(new CallgraphWriter());
    }

    CallgraphScanner(CallgraphWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    private static void requireRegularFile(CallgraphRequest request) throws IOException, InterruptedException {
        request.cancellation().throwIfCancelled();
        if (!Files.isRegularFile(request.remappedJar(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Remapped JAR is not a regular file: " + request.remappedJar());
        }
    }

    private static List<String> discover(ZipFile jar, CallgraphRequest request) throws IOException, InterruptedException {
        List<String> entries = new ArrayList<>();
        Map<String, Integer> occurrences = new TreeMap<>();
        var enumeration = jar.entries();
        while (enumeration.hasMoreElements()) {
            request.cancellation().throwIfCancelled();
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.endsWith(".class") && !name.equals("module-info.class") && !name.endsWith("/module-info.class")) {
                entries.add(name);
                occurrences.merge(name, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> occurrence : occurrences.entrySet()) {
            if (occurrence.getValue() > 1) {
                throw new IOException("Duplicate class entry in remapped JAR: " + occurrence.getKey());
            }
        }
        entries.sort(Comparator.naturalOrder());
        return List.copyOf(entries);
    }

    private static boolean hasInterruptedCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.toString() : message;
    }

    static int parserWindow(int threads) {
        return threads >= MAX_BATCH_WINDOW / 2 ? MAX_BATCH_WINDOW : Math.max(1, threads * 2);
    }

    public CallgraphSummary scan(CallgraphRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        try {
            requireRegularFile(request);
            request.progress().report("callgraph", 0, "Discovering remapped class files");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Callgraph scan cancelled", exception);
        }
        try (ZipFile jar = new ZipFile(request.remappedJar().toFile());
             ExecutorService executor = Executors.newFixedThreadPool(request.threads())) {
            List<String> entries = discover(jar, request);
            request.progress().report("callgraph", 5, "Extracting calls from " + entries.size() + " classes");
            var source = new OrderedBatchSource(jar, entries, executor, request);
            CallgraphWriter.Counts counts;
            try {
                counts = writer.write(request, source);
            } catch (Exception | Error failure) {
                source.cancelOutstanding();
                throw failure;
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            request.progress().report("callgraph", 100, "Recorded " + counts.edges() + " call edges");
            return new CallgraphSummary(counts.classes(), counts.methods(), counts.edges(), elapsed);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Callgraph scan cancelled", exception);
        } catch (IOException exception) {
            if (hasInterruptedCause(exception)) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        } catch (Exception exception) {
            if (hasInterruptedCause(exception)) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Callgraph scan failed: " + message(exception), exception);
        }
    }

    private static final class OrderedBatchSource implements CallgraphWriter.BatchSource {
        private final ZipFile jar;
        private final List<String> entries;
        private final ExecutorService executor;
        private final CallgraphRequest request;
        private final int window;
        private final ArrayDeque<PendingExtraction> futures = new ArrayDeque<>();
        private final Map<String, String> firstEntryByClass = new HashMap<>();
        private int submitted;
        private int completed;
        private int lastReportedPercent = -1;
        private long inFlightClassBytes;

        private OrderedBatchSource(ZipFile jar, List<String> entries, ExecutorService executor, CallgraphRequest request) {
            this.jar = jar;
            this.entries = entries;
            this.executor = executor;
            this.request = request;
            window = parserWindow(request.threads());
        }

        private static byte[] readClassBytes(InputStream input, CallgraphRequest request, String entryName) throws IOException, InterruptedException {
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                request.cancellation().throwIfCancelled();
                if ((long) output.size() + read > MAXIMUM_CLASS_BYTES) {
                    throw new IOException("Class entry exceeds the expanded-byte limit of " + MAXIMUM_CLASS_BYTES + ": " + entryName);
                }
                output.write(buffer, 0, read);
            }
            request.cancellation().throwIfCancelled();
            return output.toByteArray();
        }

        @Override
        public InvocationExtractor.Extraction next() throws Exception {
            fillWindow();
            if (futures.isEmpty()) {
                return null;
            }
            request.cancellation().throwIfCancelled();
            PendingExtraction pending = futures.removeFirst();
            try {
                InvocationExtractor.Extraction extraction = pending.future().get();
                String firstEntry = firstEntryByClass.putIfAbsent(extraction.className(), pending.entryName());
                if (firstEntry != null) {
                    throw new IOException("Duplicate binary class " + extraction.className() + " in remapped JAR entries " + firstEntry + " and " + pending.entryName());
                }
                completed++;
                int percent = entries.isEmpty() ? 75 : 5 + (int) (70L * completed / entries.size());
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent;
                    request.progress().report("callgraph", percent, "Extracted " + completed + " of " + entries.size() + " classes");
                }
                return extraction;
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception checked) {
                    throw checked;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw exception;
            } finally {
                inFlightClassBytes -= pending.byteLength();
            }
        }

        private void fillWindow() throws IOException, InterruptedException {
            while (futures.size() < window && submitted < entries.size()) {
                request.cancellation().throwIfCancelled();
                String name = entries.get(submitted);
                ZipEntry entry = jar.getEntry(name);
                if (entry == null) {
                    throw new IOException("Class entry disappeared from remapped JAR: " + name);
                }
                long declaredSize = entry.getSize();
                if (declaredSize > MAXIMUM_CLASS_BYTES) {
                    throw new IOException("Class entry exceeds the expanded-byte limit of " + MAXIMUM_CLASS_BYTES + ": " + name);
                }
                if (!futures.isEmpty() && (declaredSize < 0 || Math.addExact(inFlightClassBytes, declaredSize) > MAXIMUM_IN_FLIGHT_CLASS_BYTES)) {
                    return;
                }
                byte[] bytes;
                try (var input = jar.getInputStream(entry)) {
                    bytes = readClassBytes(input, request, name);
                }
                if (Math.addExact(inFlightClassBytes, bytes.length) > MAXIMUM_IN_FLIGHT_CLASS_BYTES) {
                    throw new IOException("Queued class entries exceed the expanded-byte limit of " + MAXIMUM_IN_FLIGHT_CLASS_BYTES);
                }
                request.cancellation().throwIfCancelled();
                Future<InvocationExtractor.Extraction> future = executor.submit(() -> {
                    request.cancellation().throwIfCancelled();
                    return new InvocationExtractor().extract(bytes, request.cancellation());
                });
                submitted++;
                inFlightClassBytes += bytes.length;
                futures.addLast(new PendingExtraction(name, bytes.length, future));
            }
        }

        private void cancelOutstanding() {
            futures.forEach(pending -> pending.future().cancel(true));
            executor.shutdownNow();
        }

        private record PendingExtraction(String entryName, int byteLength, Future<InvocationExtractor.Extraction> future) {
        }
    }
}