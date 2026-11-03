package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One runtime declaration whose generic argument type remains attached to its blocking handler.
 */
public final class RuntimeBindingDeclaration<A> {
    private final ToolDeclaration<A> declaration;
    private final Function<RuntimeContext, BlockingToolHandler<A>> factory;
    private volatile BlockingToolHandler<A> active;

    RuntimeBindingDeclaration(ToolDeclaration<A> declaration, Function<RuntimeContext, BlockingToolHandler<A>> factory) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public String name() {
        return declaration.name();
    }

    public ToolDeclaration<A> declaration() {
        return declaration;
    }

    public void activate(RuntimeContext context) {
        active = handler(context);
    }

    ContentToolBinding<A> create(RuntimeContext context) {
        return declaration.bindBlocking(handler(context));
    }

    private BlockingToolHandler<A> handler(RuntimeContext context) {
        return Objects.requireNonNull(factory.apply(Objects.requireNonNull(context, "context")), "runtime binding: " + name());
    }

    public void deactivate() {
        active = null;
    }

    public BlockingToolHandler<A> activeHandler() {
        return Objects.requireNonNull(active, "Runtime binding is not active: " + name());
    }

    public ContentToolBinding<A> lazyBinding(Supplier<BlockingToolHandler<A>> activeHandler) {
        Objects.requireNonNull(activeHandler, "activeHandler");
        return declaration.bindBlocking((input, cancellation) -> Objects.requireNonNull(activeHandler.get().handle(input, cancellation), "Runtime tool handler returned null: " + name()));
    }
}
