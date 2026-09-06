package dev.mcdevmcp.mcp;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolDeclarations;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.mcp.transport.McpSdkAdapter;
import dev.mcdevmcp.mcp.transport.StdioServer;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.tools.runtime.RuntimeToolModule;
import dev.mcdevmcp.tools.runtime.RuntimeBindingDeclaration;
import dev.mcdevmcp.tools.runtime.RuntimeContext;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpServerFactory implements AutoCloseable {
    private static final Duration EXECUTOR_STOP_TIMEOUT = Duration.ofSeconds(5);

    private static final AutoCloseable NO_RUNTIME = () -> {
    };

    private final AppEnvironment environment;
    private final Map<String, ToolBinding<?>> bindings;
    private final List<ToolDefinition> definitions;
    private final ResourceCatalog resourceCatalog;
    private final McpJsonMapper mapper;
    private final CloseOnce ownedRuntime;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpServerFactory(AppEnvironment environment) {
        this(environment, defaultComposition(environment));
    }

    private McpServerFactory(AppEnvironment environment, DefaultComposition composition) {
        this(environment, composition.bindings(), composition.definitions(), composition.resourceCatalog(), composition.mapper(), composition.ownedRuntime());
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        this(environment, bindings, null, ResourceCatalog.withMapper(mapper), mapper, NO_RUNTIME);
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper) {
        this(environment, bindings, null, resourceCatalog, mapper, NO_RUNTIME);
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper, AutoCloseable ownedRuntime) {
        this(environment, bindings, null, resourceCatalog, mapper, ownedRuntime);
    }

    private McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, List<ToolDefinition> definitions, ResourceCatalog resourceCatalog, McpJsonMapper mapper, AutoCloseable ownedRuntime) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.bindings = Map.copyOf(bindings);
        this.definitions = definitions == null ? null : List.copyOf(definitions);
        this.resourceCatalog = Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ownedRuntime = new CloseOnce(Objects.requireNonNull(ownedRuntime, "ownedRuntime"));
    }

    private static DefaultComposition defaultComposition(AppEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        DeclarativeComposition declarative = declarativeComposition(environment, mapper);
        try {
            return new DefaultComposition(declarative.bindings(), declarative.definitions(), ResourceCatalog.withMapper(mapper), mapper, declarative);
        } catch (RuntimeException | Error exception) {
            closeAfterFailure(declarative, exception);
            throw exception;
        }
    }

    /**
     * Opens the all-tool declarative composition used by packaging and other
     * schema-only consumers. Runtime provider resources are created only if a
     * lazy runtime binding is invoked, and are owned by this composition.
     */
    public static DeclarativeComposition declarativeComposition(AppEnvironment environment, McpJsonMapper mapper) {
        return declarativeComposition(environment, mapper, productionResourceFactory());
    }

    static DeclarativeComposition declarativeComposition(AppEnvironment environment, McpJsonMapper mapper, RuntimeResourceFactory resourceFactory) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(resourceFactory, "resourceFactory");
        PlatformPaths paths = PlatformPaths.forEnvironment(System.getProperty("os.name"), environment.values(), Path.of(System.getProperty("user.home")));
        var activation = new RuntimeToolActivation(environment, mapper, resourceFactory);
        try {
            var bindings = new LinkedHashMap<>(StaticToolModule.handlers(paths));
            activation.lazyBindings().forEach((name, binding) -> {
                if (bindings.putIfAbsent(name, binding) != null) {
                    throw new IllegalStateException("Duplicate MCP tool binding: " + name);
                }
            });
            List<ToolDefinition> definitions = ToolCatalog.declarativeDefinitions(environment, mapper, bindings);
            return new DeclarativeComposition(definitions, bindings, activation);
        } catch (RuntimeException | Error exception) {
            closeAfterFailure(activation, exception);
            throw exception;
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("mcdev-session-poll").unstarted(runnable));
    }

    static RuntimeResourceFactory productionResourceFactory() {
        return new RuntimeResourceFactory() {
            @Override
            public HttpClient createClient() {
                return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            }

            @Override
            public BridgeSession createSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment) {
                return new BridgeSession(client, mapper, environment, message -> dev.mcdevmcp.support.DebugLog.write(environment, message));
            }

            @Override
            public ScheduledExecutorService createScheduler() {
                return newScheduler();
            }

            @Override
            public RuntimeContext createContext(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment, ScheduledExecutorService scheduler) {
                return RuntimeToolModule.context(session, mapper, environment, scheduler);
            }
        };
    }

    private static void closeAfterFailure(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeExecutorAfterFailure(ExecutorService executor, Throwable failure) {
        executor.shutdownNow();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                failure.addSuppressed(new IllegalStateException("MCP blocking executor did not stop after startup failure"));
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            failure.addSuppressed(new IllegalStateException("Interrupted while stopping MCP blocking executor after startup failure", exception));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    synchronized ToolCatalog loadToolCatalog(ExecutorService blockingExecutor) {
        requireOpen();
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        if (definitions != null) {
            var adaptedBindings = new LinkedHashMap<String, ToolBinding<?>>();
            bindings.forEach((name, binding) -> adaptedBindings.put(name, binding.withBlockingExecutor(blockingExecutor)));
            return ToolCatalog.fromDefinitions(environment, mapper, definitions, adaptedBindings);
        }
        return ToolCatalog.load(environment, ToolDeclarations.all(), bindings, mapper, blockingExecutor);
    }

    public synchronized ServerDefinition loadServerDefinition(ExecutorService blockingExecutor) {
        requireOpen();
        return new ServerDefinition("mcdev-mcp", AppVersion.current(), ResourceCatalog.INSTRUCTIONS, loadToolCatalog(blockingExecutor), resourceCatalog);
    }

    public synchronized StdioServer startStdio(InputStream input, OutputStream output) {
        requireOpen();
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("MCP server factory can only start one STDIO server");
        }

        var blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ServerDefinition definition = loadServerDefinition(blockingExecutor);
            return McpSdkAdapter.startStdio(mapper, input, output, definition, blockingExecutor, ownedRuntime);
        } catch (RuntimeException | Error exception) {
            closed.set(true);
            closeExecutorAfterFailure(blockingExecutor, exception);
            closeAfterFailure(ownedRuntime, exception);
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ownedRuntime.close();
    }

    private void requireOpen() {
        if (closed.get() || ownedRuntime.isClosed()) {
            throw new IllegalStateException("MCP server factory is closed");
        }
    }

    public static final class DeclarativeComposition implements AutoCloseable {
        private final List<ToolDefinition> definitions;
        private final Map<String, ToolBinding<?>> bindings;
        private final RuntimeToolActivation activation;

        private DeclarativeComposition(List<ToolDefinition> definitions, Map<String, ToolBinding<?>> bindings, RuntimeToolActivation activation) {
            this.definitions = List.copyOf(definitions);
            this.bindings = Map.copyOf(bindings);
            this.activation = Objects.requireNonNull(activation, "activation");
        }

        public List<ToolDefinition> definitions() {
            return definitions;
        }

        private Map<String, ToolBinding<?>> bindings() {
            return bindings;
        }

        boolean runtimeActivated() {
            return activation.isActivated();
        }

        @Override
        public void close() {
            activation.close();
        }
    }

    private record DefaultComposition(Map<String, ToolBinding<?>> bindings, List<ToolDefinition> definitions, ResourceCatalog resourceCatalog, McpJsonMapper mapper, AutoCloseable ownedRuntime) {
    }

    private record RuntimeResources(BridgeSession session, HttpClient client, ScheduledExecutorService scheduler, RuntimeResourceFactory resourceFactory) implements AutoCloseable {
        @Override
        public void close() {
            Throwable failure = null;
            try {
                resourceFactory.closeSession(session);
            } catch (Throwable exception) {
                failure = exception;
            }
            try {
                resourceFactory.closeClient(client);
            } catch (Throwable exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                resourceFactory.closeScheduler(scheduler);
            } catch (Throwable exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    interface RuntimeResourceFactory {
        HttpClient createClient();

        BridgeSession createSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment);

        ScheduledExecutorService createScheduler();

        RuntimeContext createContext(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment, ScheduledExecutorService scheduler);

        default void closeSession(BridgeSession session) {
            session.close();
        }

        default void closeClient(HttpClient client) {
            client.close();
        }

        default void closeScheduler(ScheduledExecutorService scheduler) {
            scheduler.shutdownNow();
        }
    }

    private static final class RuntimeToolActivation implements AutoCloseable {
        private final AppEnvironment environment;
        private final McpJsonMapper mapper;
        private final RuntimeResourceFactory resourceFactory;
        private final List<RuntimeBindingDeclaration<?>> declarations;
        private final Map<String, ToolBinding<?>> lazyBindings;
        private RuntimeResources resources;
        private RuntimeContext context;
        private boolean closed;

        private RuntimeToolActivation(AppEnvironment environment, McpJsonMapper mapper, RuntimeResourceFactory resourceFactory) {
            this.environment = Objects.requireNonNull(environment, "environment");
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            this.resourceFactory = Objects.requireNonNull(resourceFactory, "resourceFactory");
            this.declarations = RuntimeToolModule.bindingDeclarations();
            this.lazyBindings = createLazyBindings();
        }

        private Map<String, ToolBinding<?>> lazyBindings() {
            return lazyBindings;
        }

        private Map<String, ToolBinding<?>> createLazyBindings() {
            var bindings = new LinkedHashMap<String, ToolBinding<?>>();
            for (RuntimeBindingDeclaration<?> declaration : declarations) {
                addLazyBinding(bindings, declaration);
            }
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        }

        private <A> void addLazyBinding(Map<String, ToolBinding<?>> bindings, RuntimeBindingDeclaration<A> declaration) {
            bindings.put(declaration.name(), declaration.lazyBinding(() -> activeBinding(declaration)));
        }

        private synchronized void activate() {
            if (closed) {
                throw new IllegalStateException("Declarative runtime activation is closed");
            }
            if (context != null) {
                return;
            }
            RuntimeResources owned = acquireResources();
            try {
                RuntimeContext activated = resourceFactory.createContext(owned.session(), mapper, environment, owned.scheduler());
                for (RuntimeBindingDeclaration<?> declaration : declarations) {
                    declaration.activate(activated);
                }
                context = activated;
                resources = owned;
            } catch (RuntimeException | Error exception) {
                for (RuntimeBindingDeclaration<?> declaration : declarations) {
                    declaration.deactivate();
                }
                closeAfterFailure(owned, exception);
                closed = true;
                throw exception;
            }
        }

        private RuntimeResources acquireResources() {
            HttpClient client = null;
            BridgeSession session = null;
            ScheduledExecutorService scheduler = null;
            try {
                client = resourceFactory.createClient();
                session = resourceFactory.createSession(client, mapper, environment);
                scheduler = resourceFactory.createScheduler();
                return new RuntimeResources(Objects.requireNonNull(session, "runtime session"), Objects.requireNonNull(client, "runtime client"), Objects.requireNonNull(scheduler, "runtime scheduler"), resourceFactory);
            } catch (RuntimeException | Error exception) {
                closePartialResources(resourceFactory, session, client, scheduler, exception);
                closed = true;
                throw exception;
            }
        }

        private static void closePartialResources(RuntimeResourceFactory resourceFactory, BridgeSession session, HttpClient client, ScheduledExecutorService scheduler, Throwable failure) {
            if (session != null) {
                try {
                    resourceFactory.closeSession(session);
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (client != null) {
                try {
                    resourceFactory.closeClient(client);
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (scheduler != null) {
                try {
                    resourceFactory.closeScheduler(scheduler);
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }

        private synchronized boolean isActivated() {
            return context != null;
        }

        private <A> dev.mcdevmcp.mcp.tool.api.ContentToolBinding<A> activeBinding(RuntimeBindingDeclaration<A> declaration) {
            activate();
            return declaration.activeBinding();
        }


        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (RuntimeBindingDeclaration<?> declaration : declarations) {
                declaration.deactivate();
            }
            context = null;
            if (resources != null) {
                resources.close();
            }
        }
    }

    private static final class CloseOnce implements AutoCloseable {
        private final AutoCloseable delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseOnce(AutoCloseable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } catch (RuntimeException | Error exception) {
                    throw exception;
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Unable to close owned MCP runtime", exception);
                }
            }
        }

        private boolean isClosed() {
            return closed.get();
        }
    }
}
