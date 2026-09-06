package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One runtime declaration whose generic argument type remains attached to its binder.
 */
public final class RuntimeBindingDeclaration<A> {
    private final ToolDeclaration<A> declaration;
    private final Function<RuntimeContext, ContentToolBinding<A>> factory;
    private volatile ContentToolBinding<A> active;

    RuntimeBindingDeclaration(ToolDeclaration<A> declaration, Function<RuntimeContext, ContentToolBinding<A>> factory) {
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
        active = create(context);
    }

    ContentToolBinding<A> create(RuntimeContext context) {
        return Objects.requireNonNull(factory.apply(Objects.requireNonNull(context, "context")), "runtime binding: " + name());
    }

    public void deactivate() {
        active = null;
    }

    public ContentToolBinding<A> activeBinding() {
        return Objects.requireNonNull(active, "Runtime binding is not active: " + name());
    }

    public ContentToolBinding<A> lazyBinding(Supplier<ContentToolBinding<A>> activeBinding) {
        Objects.requireNonNull(activeBinding, "activeBinding");
        return declaration.bind((input, cancellation) -> {
            CompletionStage<? extends ContentToolResult<Void>> result = activeBinding.get().invokeDecoded(input, cancellation);
            return Objects.requireNonNull(result, "Runtime tool handler returned null: " + name());
        });
    }
}
