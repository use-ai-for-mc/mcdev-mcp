package dev.mcdevmcp.app;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps the JVM's ahead-of-time cache of a packaged installation current.
 * <p>
 * The packaged launcher always names the cache. A JVM cannot use and record a cache in the same
 * run, so when the cache is missing or no longer matches the installed JAR a short separate
 * process records a new one; later launches then start from it.
 */
final class StartupCache {
    static final String USE_OPTION = "-XX:AOTCache=";
    static final String RECORD_OPTION = "-XX:AOTCacheOutput=";

    private StartupCache() {
    }

    /**
     * Best effort: a launch without a configured cache, an installation that cannot be written
     * to, or a trainer that cannot be started all leave the server running as it is.
     */
    static void refresh() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        Launch launch = new Launch(runtime.getInputArguments(), System.getProperty("java.class.path"), Path.of(System.getProperty("java.home")));
        try {
            refresh(launch, StartupCache::start);
        } catch (IOException | RuntimeException _) {
            // The cache only shortens later launches.
        }
    }

    static void refresh(Launch launch, TrainerStarter starter) throws IOException {
        Optional<Path> configured = configuredCache(launch.jvmArguments());
        if (configured.isEmpty()) {
            return;
        }
        Path cache = configured.get();
        Path directory = cache.toAbsolutePath().getParent();
        if (directory == null || !Files.isWritable(directory) || current(cache, identity(launch.classPath()))) {
            return;
        }
        starter.start(trainerCommand(launch, cache));
    }

    static Optional<Path> configuredCache(List<String> jvmArguments) {
        return jvmArguments.stream().filter(argument -> argument.startsWith(USE_OPTION)).map(argument -> Path.of(argument.substring(USE_OPTION.length()))).findFirst();
    }

    static List<String> trainerCommand(Launch launch, Path cache) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>();
        command.add(launch.javaHome().resolve("bin").resolve(windows ? "java.exe" : "java").toString());
        for (String argument : launch.jvmArguments()) {
            command.add(argument.startsWith(USE_OPTION) ? RECORD_OPTION + recording(cache) : argument);
        }
        command.add("-cp");
        command.add(launch.classPath());
        command.add(StartupCacheTrainer.class.getName());
        command.add(cache.toString());
        return List.copyOf(command);
    }

    /**
     * The JVM accepts a cache only for the exact class-path files it was recorded with, compared
     * by size and modification time, so the same facts decide when to record again.
     */
    static String identity(String classPath) throws IOException {
        StringBuilder identity = new StringBuilder(Runtime.version().toString());
        for (String entry : classPath.split(System.getProperty("path.separator"))) {
            Path file = Path.of(entry);
            if (Files.isRegularFile(file)) {
                identity.append('\n').append(file.getFileName()).append(' ').append(Files.size(file)).append(' ').append(Files.getLastModifiedTime(file).toMillis());
            }
        }
        return identity.toString();
    }

    static boolean current(Path cache, String identity) throws IOException {
        Path recorded = identityFile(cache);
        return Files.isRegularFile(cache) && Files.isRegularFile(recorded) && Files.readString(recorded, StandardCharsets.UTF_8).equals(identity);
    }

    static Path recording(Path cache) {
        return sibling(cache, ".new");
    }

    static Path identityFile(Path cache) {
        return sibling(cache, ".id");
    }

    static Path lockFile(Path cache) {
        return sibling(cache, ".lock");
    }

    private static Path sibling(Path cache, String suffix) {
        return cache.resolveSibling(cache.getFileName() + suffix);
    }

    private static void start(List<String> command) throws IOException {
        release(new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start());
    }

    /**
     * The trainer drives itself and ends on its own; it must be able to finish after a short
     * session has already closed this server.
     */
    private static void release(Process trainer) throws IOException {
        trainer.getOutputStream().close();
    }

    @FunctionalInterface
    interface TrainerStarter {
        void start(List<String> command) throws IOException;
    }

    value record Launch(List<String> jvmArguments, String classPath, Path javaHome) {
        Launch {
            jvmArguments = List.copyOf(jvmArguments);
        }
    }
}
