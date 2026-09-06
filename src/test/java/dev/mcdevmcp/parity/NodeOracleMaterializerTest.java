package dev.mcdevmcp.parity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("parity")
@Timeout(15)
class NodeOracleMaterializerTest {
    @Test
    void parsesBranchedDetachedAndAdvisoryWorktreeEntries() {
        byte[] porcelain = ("""
                            worktree C:/projects/oracle
                            HEAD 0123456789012345678901234567890123456789
                            branch refs/heads/master
                            locked maintenance reason
                            
                            worktree C:/projects/detached
                            HEAD abcdefabcdefabcdefabcdefabcdefabcdefabcd
                            detached
                            prunable gitdir file points to non-existent location
                            """).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        List<NodeOracleMaterializer.Worktree> worktrees = NodeOracleMaterializer.parseWorktreePorcelain(porcelain);

        assertEquals(2, worktrees.size());
        assertEquals(Path.of("C:/projects/oracle"), worktrees.getFirst().path());
        assertEquals("0123456789012345678901234567890123456789", worktrees.getFirst().head());
        assertEquals("refs/heads/master", worktrees.getFirst().branch());
        assertEquals(Path.of("C:/projects/detached"), worktrees.get(1).path());
        assertEquals("abcdefabcdefabcdefabcdefabcdefabcdefabcd", worktrees.get(1).head());
        assertNull(worktrees.get(1).branch());
    }

    @Test
    void acceptsAFinalEntryWithoutATrailingSeparator() {
        byte[] porcelain = "worktree C:/projects/oracle\nHEAD abc\nbranch refs/heads/master".getBytes(StandardCharsets.UTF_8);

        List<NodeOracleMaterializer.Worktree> worktrees = NodeOracleMaterializer.parseWorktreePorcelain(porcelain);

        assertEquals(1, worktrees.size());
        assertEquals(Path.of("C:/projects/oracle"), worktrees.getFirst().path());
        assertEquals("abc", worktrees.getFirst().head());
        assertEquals("refs/heads/master", worktrees.getFirst().branch());
    }

    @Test
    void rejectsMalformedPorcelainWithoutReturningPartialResults() {
        assertMalformed(new byte[0], "returned no worktrees");
        assertMalformed("HEAD abc\n".getBytes(StandardCharsets.UTF_8), "missing worktree");
        assertMalformed("worktree C:/oracle\nworktree C:/duplicate\n".getBytes(StandardCharsets.UTF_8), "Malformed git worktree porcelain line");
        assertMalformed("worktree C:/oracle\nunknown value\n".getBytes(StandardCharsets.UTF_8), "Malformed git worktree porcelain line");
        assertMalformed("worktree C:/oracle\nbranch refs/heads/master\ndetached\n".getBytes(StandardCharsets.UTF_8), "both branch and detached");
        assertMalformed("worktree \n".getBytes(StandardCharsets.UTF_8), "Malformed git worktree porcelain line");
        assertMalformed(new byte[]{(byte) 0xC3, 0x28}, "Invalid UTF-8");
    }

    @Test
    void rejectsASymlinkedScratchAncestor(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        try {
            Files.createSymbolicLink(workspace.resolve(".superpowers"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> NodeOracleMaterializer.scratchLayout(workspace));

        assertTrue(failure.getMessage().contains("linked or redirected scratch ancestor"), failure::getMessage);
        assertTrue(Files.exists(outside));
    }

    @Test
    void concurrentScratchAllocationsAreIndependentAndCleanupIsInstanceScoped(@TempDir Path temporaryDirectory) throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path first;
        Path second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstAllocation = executor.submit(() -> NodeOracleMaterializer.createScratchDirectory(workspace));
            var secondAllocation = executor.submit(() -> NodeOracleMaterializer.createScratchDirectory(workspace));
            first = firstAllocation.get();
            second = secondAllocation.get();
        }

        assertNotEquals(first, second);
        Files.writeString(first.resolve("owned-by-first"), "first", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("owned-by-second"), "second", StandardCharsets.UTF_8);

        NodeOracleMaterializer.deleteScratchDirectory(workspace, first);

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second.resolve("owned-by-second")));
        NodeOracleMaterializer.deleteScratchDirectory(workspace, second);
        assertFalse(Files.exists(second));
    }

    @Test
    void commandTimeoutTerminatesTheWholeProcessTree(@TempDir Path temporaryDirectory) throws Exception {
        Path childPidFile = temporaryDirectory.resolve("child.pid");
        IOException failure = assertThrows(IOException.class, () -> NodeOracleMaterializer.run(temporaryDirectory, fixtureCommand("materializer-tree", childPidFile.toString()), Map.of(), Duration.ofSeconds(2)));

        assertTrue(failure.getMessage().contains("Command timed out after PT2S"), failure::getMessage);
        long childPid = Long.parseLong(Files.readString(childPidFile, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false), "Timed-out command left its child process alive");
    }

    @Test
    void successfulRootExitStillTerminatesTrackedDescendants(@TempDir Path temporaryDirectory) throws Exception {
        Path childPidFile = temporaryDirectory.resolve("child.pid");

        byte[] output = NodeOracleMaterializer.run(temporaryDirectory, fixtureCommand("materializer-tree-root-exits", childPidFile.toString()), Map.of(), Duration.ofSeconds(5));

        assertEquals(0, output.length);
        long childPid = Long.parseLong(Files.readString(childPidFile, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false), "Successful command left its tracked descendant alive");
    }

    private static void assertMalformed(byte[] porcelain, String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NodeOracleMaterializer.parseWorktreePorcelain(porcelain));
        assertTrue(failure.getMessage().contains(expectedMessage), failure::getMessage);
    }

    private static List<String> fixtureCommand(String... arguments) {
        String configuredJava = System.getProperty("mcdevMcpJava");
        String java = configuredJava == null || configuredJava.isBlank() ? ProcessHandle.current().info().command().orElseThrow() : configuredJava;
        var command = new java.util.ArrayList<String>(arguments.length + 4);
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(McpProcessFixtureMain.class.getName());
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }
}
