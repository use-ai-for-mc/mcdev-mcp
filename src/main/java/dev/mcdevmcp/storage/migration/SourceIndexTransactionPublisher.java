package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.symbols.AtomicSymbolIndex;
import dev.mcdevmcp.storage.symbols.IndexLock;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Publishes a closed source/index pair while retaining verified original bytes.
 */
public final class SourceIndexTransactionPublisher {
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final String DATABASE = "symbols.jsonl";
    private static final String LEGACY_DATABASE = "symbols.mv.db";
    private static final String DATABASE_LOCK = DATABASE + ".lock";
    private final SourcePublicationHook hook;
    private final CachePathBoundary boundary;

    public SourceIndexTransactionPublisher() {
        this((_, _) -> {
        });
    }

    SourceIndexTransactionPublisher(SourcePublicationHook hook) {
        this(hook, null);
    }

    private SourceIndexTransactionPublisher(SourcePublicationHook hook, CachePathBoundary boundary) {
        this.hook = Objects.requireNonNull(hook);
        this.boundary = boundary;
    }

    private static SourceTreeInventory newIndex(SourceTreeInventory before, SourceTreeInventory database) {
        var entries = new ArrayList<SourceInventoryEntry>();
        for (SourceInventoryEntry entry : before.entries()) if (!managed(entry.relativePath())) entries.add(entry);
        if (entries.isEmpty()) entries.add(new SourceInventoryEntry("", SourceEntryKind.DIRECTORY, 0, ""));
        SourceInventoryEntry file = database.entries().getFirst();
        entries.add(new SourceInventoryEntry(DATABASE, file.kind(), file.size(), file.sha256()));
        entries.sort(Comparator.comparing(SourceInventoryEntry::relativePath));
        return SourceTreeInventory.of(true, entries);
    }

    private static List<String> managedNames(SourceTreeInventory index) throws IOException {
        var names = new ArrayList<String>();
        for (SourceInventoryEntry entry : index.entries()) {
            if (managed(entry.relativePath())) {
                if (entry.kind() != SourceEntryKind.FILE || entry.relativePath().contains("/")) {
                    throw new IOException("Unsafe symbol index artifact: " + entry.relativePath());
                }
                names.add(entry.relativePath());
            }
        }
        return names;
    }

    private static boolean managed(String name) {
        return Set.of(DATABASE, DATABASE + ".bak", LEGACY_DATABASE, LEGACY_DATABASE + ".bak").contains(name);
    }

    private static SourceTreeInventory entryInventory(SourceTreeInventory index, String name) {
        return index.entries().stream().filter(entry -> entry.relativePath().equals(name)).findFirst().map(entry -> SourceTreeInventory.of(true, List.of(new SourceInventoryEntry("", entry.kind(), entry.size(), entry.sha256())))).orElseGet(() -> SourceTreeInventory.of(false, List.of()));
    }

    private static void requireWrite(PlatformPaths paths, MinecraftVersion version, VersionOperationLease lease) throws IOException {
        lease.require(paths, version);
        if (!lease.isWrite()) throw new IOException("Source publication requires version write lease");
    }

    private static void requireDirectory(SourceTreeInventory inventory, String label) throws IOException {
        if (!inventory.present() || inventory.entries().getFirst().kind() != SourceEntryKind.DIRECTORY) {
            throw new IOException("Expected " + label + " directory");
        }
    }

    private static void requireFile(SourceTreeInventory inventory, String label) throws IOException {
        if (!inventory.present() || inventory.entries().size() != 1 || inventory.entries().getFirst().kind() != SourceEntryKind.FILE) {
            throw new IOException("Expected " + label + " regular file");
        }
    }

    private static void verify(Object expected, Object actual, String label) throws IOException {
        if (!expected.equals(actual)) throw new IOException(label);
    }

    private static Path migrations(PlatformPaths paths, MinecraftVersion version) {
        return paths.cacheRoot().resolve("migrations").resolve(version.value());
    }

    private static Path pendingPath(PlatformPaths paths, MinecraftVersion version) {
        return migrations(paths, version).resolve("pending.json");
    }

    private static Path stampPath(PlatformPaths paths, MinecraftVersion version) {
        return paths.versionCache(version).resolve("source-preparation.json");
    }

    public SourceIndexSnapshot captureBefore(PlatformPaths paths, MinecraftVersion version, VersionOperationLease lease, Cancellation cancellation) throws IOException {
        requireWrite(paths, version, lease);
        if (boundary == null) {
            return new SourceIndexTransactionPublisher(hook, lease.boundary()).captureBefore(lease.resolvedPaths(), version, lease, cancellation);
        }
        boundary.require(paths);
        try (var lock = canonicalDatabaseLock(paths, version)) {
            if (!lock.isHeld()) throw new IOException("Database publication lock not held");
            refusePending(paths, version);
            return capture(paths, version, cancellation);
        }
    }

    public SourcePublicationResult publish(PlatformPaths paths, MinecraftVersion version, VersionOperationLease lease, Path transactionRoot, Path candidateSource, Path candidateDatabase, Path candidateStamp, SourceIndexSnapshot expectedBefore, Cancellation cancellation) throws IOException {
        return publish(paths, version, lease, transactionRoot, candidateSource, candidateDatabase, candidateStamp, expectedBefore, SourcePublicationMode.REPLACE_SOURCES, cancellation);
    }

    public SourcePublicationResult publish(PlatformPaths paths, MinecraftVersion version, VersionOperationLease lease, Path transactionRoot, Path candidateSource, Path candidateDatabase, Path candidateStamp, SourceIndexSnapshot expectedBefore, SourcePublicationMode mode, Cancellation cancellation) throws IOException {
        requireWrite(paths, version, lease);
        if (boundary == null) {
            return new SourceIndexTransactionPublisher(hook, lease.boundary()).publish(lease.resolvedPaths(), version, lease, transactionRoot, candidateSource, candidateDatabase, candidateStamp, expectedBefore, mode, cancellation);
        }
        boundary.require(paths);
        Objects.requireNonNull(mode, "mode");
        candidateSource = checked(candidateSource);
        candidateDatabase = checked(candidateDatabase);
        candidateStamp = checked(candidateStamp);
        Path transaction = validateTransaction(paths, version, transactionRoot);
        if (mode == SourcePublicationMode.REPLACE_SOURCES) {
            requireCandidate(transaction, candidateSource);
        }
        else if (!candidateSource.toAbsolutePath().normalize().equals(paths.sourceRoot(version).toAbsolutePath().normalize())) {
            throw new IOException("Reused source must be the canonical source root");
        }
        requireCandidate(transaction, candidateDatabase);
        requireCandidate(transaction, candidateStamp);
        if (candidateSource.startsWith(candidateDatabase) || candidateDatabase.startsWith(candidateSource) || candidateStamp.startsWith(candidateSource) || candidateSource.startsWith(candidateStamp) || candidateStamp.equals(candidateDatabase)) {
            throw new IOException("Overlapping candidate paths");
        }
        try (var lock = canonicalDatabaseLock(paths, version)) {
            if (!lock.isHeld()) throw new IOException("Database publication lock not held");
            refusePending(paths, version);
            verify(expectedBefore, capture(paths, version, cancellation), "Original cache changed during staging");
            SourceTreeInventory source = inventory(candidateSource, cancellation);
            SourceTreeInventory database = inventory(candidateDatabase, cancellation);
            SourceTreeInventory stamp = inventory(candidateStamp, cancellation);
            requireDirectory(source, "candidate source");
            requireFile(database, "candidate database");
            requireFile(stamp, "candidate stamp");
            if (mode == SourcePublicationMode.REUSE_SOURCES) {
                verify(expectedBefore.source(), source, "Reused source changed");
            }
            validatePair(paths, version, candidateSource, candidateDatabase, candidateStamp, source);
            SourceIndexSnapshot candidate = new SourceIndexSnapshot(source, newIndex(expectedBefore.index(), database), stamp);
            Path old = transaction.resolve("old");
            Files.createDirectory(checked(old));
            copy(paths.sourceRoot(version), old.resolve("client"), expectedBefore.source(), cancellation);
            copy(paths.indexRoot(version), old.resolve("index"), expectedBefore.index(), cancellation);
            copy(stampPath(paths, version), old.resolve("source-preparation.json"), expectedBefore.stamp(), cancellation);
            verifyRetained(transaction, expectedBefore);
            verify(expectedBefore, capture(paths, version, cancellation), "Original cache changed while snapshotting");
            preflightStore(paths, version, transaction, candidateSource, candidateDatabase, candidateStamp);
            var journal = new SourcePublicationJournal(1, version, paths.cacheRoot().toAbsolutePath().normalize().toString(), UUID.fromString(transaction.getFileName().toString()), SourcePublicationPhase.PREPARED, mode, expectedBefore, candidate);
            writeNew(transaction.resolve("before.json"), expectedBefore);
            writeNew(transaction.resolve("candidate.json"), candidate);
            SourceTreeInventory.checkCancelled(cancellation);
            writeJournal(paths, version, transaction, journal);
            try {
                hook.at("PREPARED", transaction);
                journal = advance(paths, version, transaction, journal, SourcePublicationPhase.BACKING_UP, cancellation);
                Path held = transaction.resolve("held");
                Files.createDirectory(checked(held));
                if (mode == SourcePublicationMode.REPLACE_SOURCES) {
                    detach(paths.sourceRoot(version), held.resolve("client"), expectedBefore.source());
                }
                Files.createDirectory(checked(held.resolve("index")));
                for (String name : managedNames(expectedBefore.index())) {
                    detach(paths.indexRoot(version).resolve(name), held.resolve("index").resolve(name), entryInventory(expectedBefore.index(), name));
                }
                detach(stampPath(paths, version), held.resolve("source-preparation.json"), expectedBefore.stamp());
                journal = advance(paths, version, transaction, journal, SourcePublicationPhase.BACKED_UP, cancellation);
                journal = advance(paths, version, transaction, journal, SourcePublicationPhase.INSTALLING_SOURCE, cancellation);
                if (mode == SourcePublicationMode.REPLACE_SOURCES) move(candidateSource, paths.sourceRoot(version));
                verify(source, inventory(paths.sourceRoot(version), cancellation), "Installed source differs");
                journal = advance(paths, version, transaction, journal, SourcePublicationPhase.INSTALLING_DATABASE, cancellation);
                move(candidateDatabase, paths.symbolIndex(version));
                journal = advance(paths, version, transaction, journal, SourcePublicationPhase.INSTALLING_STAMP, cancellation);
                move(candidateStamp, stampPath(paths, version));
                journal = advance(paths, version, transaction, journal, SourcePublicationPhase.VALIDATING_PAIR, cancellation);
                validateCommitted(paths, version, transaction, journal);
                SourceTreeInventory.checkCancelled(cancellation);
                journal = journal.withPhase(SourcePublicationPhase.COMMITTED);
                writeJournal(paths, version, transaction, journal);
                hook.at("COMMITTED", transaction);
                archiveCommitted(transaction, journal);
                clearPending(paths, version, journal);
                return new SourcePublicationResult(transaction, source);
            } catch (IOException | RuntimeException failure) {
                boolean interrupted = Thread.interrupted();
                try {
                    SourcePublicationJournal persisted = readJournal(paths, version);
                    if (persisted.phase() == SourcePublicationPhase.COMMITTED) {
                        validateCommitted(paths, version, transaction, persisted);
                        archiveCommitted(transaction, persisted);
                        clearPending(paths, version, persisted);
                        return new SourcePublicationResult(transaction, source);
                    }
                    rollback(paths, version, transaction, persisted);
                } catch (IOException | RuntimeException recoveryFailure) {
                    IOException pending = new IOException("Source/index recovery required at " + pendingPath(paths, version), recoveryFailure);
                    pending.addSuppressed(failure);
                    throw pending;
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
                throw failure;
            }
        }
    }

    public void recover(PlatformPaths paths, MinecraftVersion version, VersionOperationLease lease) throws IOException {
        requireWrite(paths, version, lease);
        if (boundary == null) {
            new SourceIndexTransactionPublisher(hook, lease.boundary()).recover(lease.resolvedPaths(), version, lease);
            return;
        }
        boundary.require(paths);
        if (!Files.exists(checked(pendingPath(paths, version)), LinkOption.NOFOLLOW_LINKS)) return;
        try (var lock = canonicalDatabaseLock(paths, version)) {
            if (!lock.isHeld()) throw new IOException("Database recovery lock not held");
            SourcePublicationJournal journal = readJournal(paths, version);
            Path transaction = validateTransaction(paths, version, migrations(paths, version).resolve(journal.transactionId().toString()));
            boolean interrupted = Thread.interrupted();
            try {
                if (journal.phase() == SourcePublicationPhase.COMMITTED) {
                    validateCommitted(paths, version, transaction, journal);
                    archiveCommitted(transaction, journal);
                    clearPending(paths, version, journal);
                }
                else {
                    rollback(paths, version, transaction, journal);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void rollback(PlatformPaths paths, MinecraftVersion version, Path transaction, SourcePublicationJournal journal) throws IOException {
        verifyRetained(transaction, journal.before());
        Path failed = transaction.resolve("failed");
        Files.createDirectories(checked(failed));
        if (journal.mode() == SourcePublicationMode.REPLACE_SOURCES) {
            restore(paths.sourceRoot(version), transaction.resolve("old/client"), failed.resolve("client"), journal.before().source(), journal.candidate().source());
        }
        else {
            verify(journal.before().source(), inventory(paths.sourceRoot(version), Cancellation.none()), "Reused source changed; recovery remains pending");
        }
        Set<String> names = new TreeSet<>(managedNames(journal.before().index()));
        names.add(DATABASE);
        for (String name : names) {
            restore(paths.indexRoot(version).resolve(name), transaction.resolve("old/index").resolve(name), failed.resolve(name), entryInventory(journal.before().index(), name), entryInventory(journal.candidate().index(), name));
        }
        restore(stampPath(paths, version), transaction.resolve("old/source-preparation.json"), failed.resolve("source-preparation.json"), journal.before().stamp(), journal.candidate().stamp());
        verify(journal.before(), capture(paths, version, Cancellation.none()), "Rollback did not restore original cache");
        verifyRetained(transaction, journal.before());
        writeOwned(transaction.resolve("rolled-back.json"), journal);
        clearPending(paths, version, journal);
    }

    private void restore(Path canonical, Path snapshot, Path failed, SourceTreeInventory before, SourceTreeInventory candidate) throws IOException {
        SourceTreeInventory actual = inventory(canonical, Cancellation.none());
        if (actual.equals(before)) return;
        if (actual.present()) {
            if (!actual.equals(candidate)) {
                throw new IOException("Unknown canonical contents preserved at " + canonical);
            }
            Path quarantine = failed.resolveSibling(failed.getFileName() + "-" + UUID.randomUUID());
            move(canonical, quarantine);
            verify(candidate, inventory(quarantine, Cancellation.none()), "Quarantined candidate changed");
        }
        if (before.present()) {
            Path staging = failed.resolveSibling("restore-" + UUID.randomUUID());
            copy(snapshot, staging, before, Cancellation.none());
            move(staging, canonical);
        }
        verify(before, inventory(canonical, Cancellation.none()), "Restored artifact differs");
    }

    private void detach(Path canonical, Path held, SourceTreeInventory inventory) throws IOException {
        verify(inventory, inventory(canonical, Cancellation.none()), "Original changed before detachment");
        if (inventory.present()) move(canonical, held);
        verify(inventory, inventory(held, Cancellation.none()), "Original changed during detachment");
    }

    private SourcePublicationJournal advance(PlatformPaths paths, MinecraftVersion version, Path transaction, SourcePublicationJournal journal, SourcePublicationPhase phase, Cancellation cancellation) throws IOException {
        SourceTreeInventory.checkCancelled(cancellation);
        SourcePublicationJournal next = journal.withPhase(phase);
        writeJournal(paths, version, transaction, next);
        hook.at(phase.name(), transaction);
        return next;
    }

    private SourceIndexSnapshot capture(PlatformPaths paths, MinecraftVersion version, Cancellation cancellation) throws IOException {
        SourceTreeInventory source = inventory(paths.sourceRoot(version), cancellation);
        if (source.present()) requireDirectory(source, "existing source");
        SourceTreeInventory stamp = inventory(stampPath(paths, version), cancellation);
        if (stamp.present()) requireFile(stamp, "existing stamp");
        return new SourceIndexSnapshot(source, SourceTreeInventory.capture(paths.indexRoot(version), boundary, cancellation, Set.of(DATABASE_LOCK)), stamp);
    }

    private void verifyRetained(Path transaction, SourceIndexSnapshot before) throws IOException {
        verify(before.source(), inventory(transaction.resolve("old/client"), Cancellation.none()), "Retained source snapshot differs");
        verify(before.index(), inventory(transaction.resolve("old/index"), Cancellation.none()), "Retained index snapshot differs");
        verify(before.stamp(), inventory(transaction.resolve("old/source-preparation.json"), Cancellation.none()), "Retained stamp snapshot differs");
    }

    private void validateCommitted(PlatformPaths paths, MinecraftVersion version, Path transaction, SourcePublicationJournal journal) throws IOException {
        verify(journal.candidate(), capture(paths, version, Cancellation.none()), "Published pair identity differs");
        validatePair(paths, version, paths.sourceRoot(version), paths.symbolIndex(version), stampPath(paths, version), journal.candidate().source());
        verifyRetained(transaction, journal.before());
        if (journal.mode() == SourcePublicationMode.REPLACE_SOURCES) {
            verify(journal.before().source(), inventory(transaction.resolve("held/client"), Cancellation.none()), "Held original source changed");
        }
        for (String name : managedNames(journal.before().index())) {
            verify(entryInventory(journal.before().index(), name), inventory(transaction.resolve("held/index").resolve(name), Cancellation.none()), "Held original H2 artifact changed");
        }
        verify(journal.before().stamp(), inventory(transaction.resolve("held/source-preparation.json"), Cancellation.none()), "Held original stamp changed");
        verify(journal.candidate(), capture(paths, version, Cancellation.none()), "Published pair changed during final validation");
    }

    private void validatePair(PlatformPaths paths, MinecraftVersion version, Path source, Path database, Path stamp, SourceTreeInventory inventory) throws IOException {
        requireFile(inventory(database, Cancellation.none()), "closed candidate database");
        SourcePreparationStamp preparation = SourceProvenance.read(checked(stamp)).orElseThrow(() -> new IOException("Candidate stamp is absent"));
        verify(inventory, preparation.inventory(), "Candidate stamp source inventory differs");
        if (!Path.of(preparation.inputs().remappedJar().path()).toAbsolutePath().normalize().equals(paths.remappedJar(version).toAbsolutePath().normalize())) {
            throw new IOException("Candidate stamp remapped JAR path differs");
        }
        for (SourceArtifactIdentity artifact : preparation.inputs().classpath()) validateInput(artifact);
        validateInput(preparation.inputs().remappedJar());
        checked(database);
        SymbolIndexData index;
        try {
            index = SymbolIndexFiles.read(database);
        } catch (IOException exception) {
            throw new IOException("Invalid candidate symbol database", exception);
        }
        SymbolIndexData.Header header = index.header();
        if (!version.value().equals(header.minecraftVersion()) || !paths.sourceRoot(version).toAbsolutePath().normalize().equals(Path.of(header.sourceRoot()).toAbsolutePath().normalize()) || !preparation.inputs().remappedJar().sha256().equals(header.remappedJarSha256())) {
            throw new IOException("Candidate database final source identity differs");
        }
        Set<String> sourcePaths = new LinkedHashSet<>();
        for (SymbolIndexData.TypeRow type : index.types()) {
            if (type.namespace().equals(SourceNamespace.MINECRAFT.wireName())) {
                sourcePaths.add(type.sourcePath());
            }
        }
        for (String sourcePath : sourcePaths) {
            Path relative = Path.of(sourcePath);
            Path resolved = source.toAbsolutePath().normalize().resolve(relative).normalize();
            if (relative.isAbsolute() || !resolved.startsWith(source.toAbsolutePath().normalize()) || !Files.isRegularFile(checked(resolved), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Candidate index source path is unsafe or missing: " + relative);
            }
        }
        verify(inventory, inventory(source, Cancellation.none()), "Candidate source changed while validating");
        requireFile(inventory(stamp, Cancellation.none()), "candidate stamp");
    }

    private void validateInput(SourceArtifactIdentity artifact) throws IOException {
        SourceTreeInventory inventory = inventory(Path.of(artifact.path()), Cancellation.none());
        requireFile(inventory, "source input");
        SourceInventoryEntry actual = inventory.entries().getFirst();
        if (actual.size() != artifact.size() || !actual.sha256().equals(artifact.sha256())) {
            throw new IOException("Source input changed: " + artifact.path());
        }
    }

    private void copy(Path source, Path target, SourceTreeInventory inventory, Cancellation cancellation) throws IOException {
        if (!inventory.present()) return;
        checked(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        for (SourceInventoryEntry entry : inventory.entries()) {
            SourceTreeInventory.checkCancelled(cancellation);
            Path from = source.resolve(entry.relativePath());
            Path to = target.resolve(entry.relativePath());
            checked(from);
            checked(to);
            if (entry.kind() == SourceEntryKind.DIRECTORY) {
                Files.createDirectory(to);
            }
            else {
                Files.createDirectories(checked(to.getParent()));
                checked(to);
                Files.copy(from, to, LinkOption.NOFOLLOW_LINKS);
                try (var channel = FileChannel.open(to, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
        }
        verify(inventory, inventory(target, cancellation), "Retained copy differs from captured inventory");
    }

    private void move(Path source, Path target) throws IOException {
        hook.at("BEFORE_MOVE", target);
        checked(source);
        checked(target);
        Files.createDirectories(checked(target.getParent()));
        checked(target);
        if (!Files.getFileStore(source).equals(Files.getFileStore(target.getParent()))) {
            throw new IOException("Cross-filesystem migration move refused");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        // No-option moves retain ordinary provider semantics; they do not promise adversarial race exclusion.
        Files.move(source, target);
        hook.at("AFTER_MOVE", target);
    }

    private void preflightStore(PlatformPaths paths, MinecraftVersion version, Path transaction, Path... candidates) throws IOException {
        checked(transaction);
        var store = Files.getFileStore(transaction);
        for (Path path : candidates) {
            if (!store.equals(Files.getFileStore(path))) {
                throw new IOException("Cross-filesystem migration candidate refused");
            }
        }
        Files.createDirectories(checked(paths.versionCache(version)));
        if (!store.equals(Files.getFileStore(paths.versionCache(version))) || !store.equals(Files.getFileStore(paths.indexRoot(version)))) {
            throw new IOException("Cross-filesystem migration target refused");
        }
        Path probe = transaction.resolve("move-preflight");
        Files.createDirectory(checked(probe));
        Path source = probe.resolve("source");
        Path occupied = probe.resolve("occupied");
        writeNew(source, "source");
        writeNew(occupied, "occupied");
        try {
            Files.move(checked(source), checked(occupied));
            throw new IOException("Provider replaced an occupied move target during migration preflight");
        } catch (FileAlreadyExistsException expected) {
            if (!Arrays.equals(Files.readAllBytes(source), JSON.writeValueAsBytes("source")) || !Arrays.equals(Files.readAllBytes(occupied), JSON.writeValueAsBytes("occupied"))) {
                throw new IOException("Provider changed preflight artifacts on a refused move");
            }
        }
        Files.move(checked(source), checked(probe.resolve("moved")));
    }

    private Path validateTransaction(PlatformPaths paths, MinecraftVersion version, Path transaction) throws IOException {
        Path normalized = checked(transaction);
        if (!Objects.equals(normalized.getParent(), migrations(paths, version).toAbsolutePath().normalize())) {
            throw new IOException("Invalid migration transaction location");
        }
        try {
            if (!UUID.fromString(normalized.getFileName().toString()).toString().equals(normalized.getFileName().toString())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid migration transaction identifier", exception);
        }
        checked(normalized);
        return normalized;
    }

    private void requireCandidate(Path transaction, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(transaction) || normalized.equals(transaction)) {
            throw new IOException("Candidate outside migration transaction: " + candidate);
        }
        String first = transaction.relativize(normalized).getName(0).toString();
        if (Set.of("old", "held", "failed").contains(first)) throw new IOException("Candidate overlaps retained area");
        checked(normalized);
    }

    private void refusePending(PlatformPaths paths, MinecraftVersion version) throws IOException {
        if (Files.exists(checked(pendingPath(paths, version)), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source/index recovery required at " + pendingPath(paths, version));
        }
    }

    private SourcePublicationJournal readJournal(PlatformPaths paths, MinecraftVersion version) throws IOException {
        Path pointer = pendingPath(paths, version);
        checked(pointer);
        try {
            byte[] bytes = Files.readAllBytes(pointer);
            SourcePublicationJournal journal = JSON.readValue(bytes, SourcePublicationJournal.class);
            if (!Arrays.equals(bytes, JSON.writeValueAsBytes(journal))) {
                throw new IOException("Noncanonical migration journal");
            }
            if (!journal.minecraftVersion().equals(version) || !journal.cacheRoot().equals(paths.cacheRoot().toAbsolutePath().normalize().toString())) {
                throw new IOException("Migration journal identity mismatch");
            }
            requireDirectory(journal.candidate().source(), "journal candidate source");
            requireDirectory(journal.before().index(), "journal original index");
            requireDirectory(journal.candidate().index(), "journal candidate index");
            requireFile(journal.candidate().stamp(), "journal candidate stamp");
            SourceTreeInventory database = entryInventory(journal.candidate().index(), DATABASE);
            requireFile(database, "journal candidate database");
            verify(newIndex(journal.before().index(), database), journal.candidate().index(), "Journal changes unrelated index artifacts");
            if (journal.mode() == SourcePublicationMode.REUSE_SOURCES) {
                verify(journal.before().source(), journal.candidate().source(), "Journal changes reused sources");
            }
            Path transaction = validateTransaction(paths, version, migrations(paths, version).resolve(journal.transactionId().toString()));
            byte[] transactionBytes = Files.readAllBytes(checked(transaction.resolve("journal.json")));
            SourcePublicationJournal transactionJournal = JSON.readValue(transactionBytes, SourcePublicationJournal.class);
            if (!Arrays.equals(transactionBytes, JSON.writeValueAsBytes(transactionJournal))) {
                throw new IOException("Noncanonical transaction journal");
            }
            if (!journal.withPhase(transactionJournal.phase()).equals(transactionJournal) || transactionJournal.phase().ordinal() < journal.phase().ordinal() || transactionJournal.phase().ordinal() > journal.phase().ordinal() + 1) {
                throw new IOException("Conflicting migration journal phases");
            }
            journal = transactionJournal;
            verify(journal.before(), JSON.readValue(Files.readAllBytes(checked(transaction.resolve("before.json"))), SourceIndexSnapshot.class), "Before manifest differs");
            verify(journal.candidate(), JSON.readValue(Files.readAllBytes(checked(transaction.resolve("candidate.json"))), SourceIndexSnapshot.class), "Candidate manifest differs");
            return journal;
        } catch (RuntimeException exception) {
            throw new IOException("Corrupt migration journal at " + pointer, exception);
        }
    }

    private void writeJournal(PlatformPaths paths, MinecraftVersion version, Path transaction, SourcePublicationJournal journal) throws IOException {
        boundary.revalidate();
        writeNew(transaction.resolve("phase-" + journal.phase().name() + ".json"), journal);
        writeOwned(transaction.resolve("journal.json"), journal);
        writeOwned(pendingPath(paths, version), journal);
    }

    private void archiveCommitted(Path transaction, SourcePublicationJournal journal) throws IOException {
        Path archive = transaction.resolve("committed.json");
        checked(archive);
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            verify(journal, JSON.readValue(Files.readAllBytes(archive), SourcePublicationJournal.class), "Committed archive differs");
        }
        else {
            writeNew(archive, journal);
        }
    }

    private void writeNew(Path path, Object value) throws IOException {
        var bytes = JSON.writeValueAsBytes(value);
        try (var channel = FileChannel.open(checked(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int remaining = bytes.length;
            while (remaining > 0) {
                remaining -= channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void writeOwned(Path path, Object value) throws IOException {
        checked(path);
        Files.createDirectories(checked(path.getParent()));
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".new");
        writeNew(temporary, value);
        Files.move(checked(temporary), checked(path), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void clearPending(PlatformPaths paths, MinecraftVersion version, SourcePublicationJournal expected) throws IOException {
        verify(expected, readJournal(paths, version), "Pending journal changed");
        Files.delete(checked(pendingPath(paths, version)));
    }

    private Path checked(Path path) throws IOException {
        return Objects.requireNonNull(boundary, "Publication requires an explicit cache boundary").resolve(path);
    }

    private IndexLock canonicalDatabaseLock(PlatformPaths paths, MinecraftVersion version) throws IOException {
        Path database = checked(paths.symbolIndex(version));
        Path lockPath = database.resolveSibling(database.getFileName() + ".lock");
        checked(lockPath);
        IndexLock lock = IndexLock.write(database, AtomicSymbolIndex.WRITE_LOCK_TIMEOUT);
        try {
            checked(lockPath);
            return lock;
        } catch (IOException | RuntimeException failure) {
            try {
                lock.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private SourceTreeInventory inventory(Path path, Cancellation cancellation) throws IOException {
        return SourceTreeInventory.capture(path, Objects.requireNonNull(boundary), cancellation);
    }
}