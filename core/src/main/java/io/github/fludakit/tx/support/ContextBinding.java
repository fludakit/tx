package io.github.fludakit.tx.support;

/**
 * One live binding of a {@link TransactionContext} to a call scope.
 *
 * <p>Threads created inside the scope inherit the <em>binding</em>, not the context itself, and
 * the owning scope clears it when the scope ends. Because inherited threads share the very same
 * handle, clearing it revokes the context everywhere at once — the context is never allowed to
 * outlive the transaction that produced it, even when it was handed down through an
 * {@link InheritableThreadLocal} fallback.</p>
 */
final class ContextBinding {

    private volatile TransactionContext context;

    ContextBinding(TransactionContext context) {
        this.context = context;
    }

    TransactionContext get() {
        return context;
    }

    void close() {
        context = null;
    }
}
