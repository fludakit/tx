package io.github.fludakit.tx.support;

import java.util.concurrent.Callable;

/**
 * Holds the current {@link TransactionContext} for the calling thread/scope.
 *
 * <p>This is the base (JDK 21) implementation backed by an {@link InheritableThreadLocal} that
 * carries a {@link ContextBinding} — the handle of the transaction context this thread participates
 * in. On JDK 25+ the multi-release override carries the state via {@code ScopedValue} so it also
 * propagates to structured-concurrency subtasks; that override has no fallback and therefore never
 * inherits a context it did not receive through a live scope.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code TransactionContextHolder} provides thread-local storage for the current transaction
 * context. It is the single point of access for retrieving the active transaction context and
 * binding a context for the duration of a transactional operation.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // In TransactionManager.getTransaction()
 * TransactionContext context = new TransactionContext(true);
 * context.bindResource(Connection.class, connection);
 * 
 * // In CDI interceptor or transaction boundary
 * return TransactionContextHolder.call(context, () -> {
 *     // Transaction context is now active on this thread
 *     TransactionContext ctx = TransactionContextHolder.get();
 *     // ... perform transactional operations ...
 *     return result;
 * });
 * // Context is automatically unbound after the action completes
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each thread has its own transaction context. The {@link #call}
 * method ensures proper cleanup even if the action throws an exception.</p>
 *
 * <h2>Lifetime of an inherited context</h2>
 * <p>A context handed to {@link #call} is closed when the call scope ends, which revokes it for
 * every thread that was created while the scope was active and inherited it — a thread that outlives
 * the transaction sees no context at all instead of a completed one.</p>
 *
 * <h2>Nested Transactions</h2>
 * <p>The {@link #call} method supports nested transactions. If a context is already bound when
 * {@code call} is invoked, the previous context is saved and restored after the action completes.
 * This allows transaction suspension and resumption.</p>
 *
 * <h2>Multi-Release JAR</h2>
 * <p>This is the JDK 21 base implementation using {@link InheritableThreadLocal}. The JDK 25+
 * multi-release override uses {@code ScopedValue} for better integration with structured
 * concurrency and virtual threads.</p>
 *
 * @see TransactionContext
 * @see io.github.fludakit.tx.TransactionManager
 */
public final class TransactionContextHolder {

    private static final InheritableThreadLocal<ContextBinding> BINDING = new InheritableThreadLocal<>();

    private TransactionContextHolder() {
    }

    /**
     * Returns the bound context, or {@code null} if none is bound.
     *
     * <p>A context that already completed is never returned: contexts must not outlive the
     * transaction that produced them, so a leftover inherited binding is treated as absent.</p>
     *
     * @return the current transaction context, or {@code null} if no transaction is active
     */
    public static TransactionContext get() {
        ContextBinding binding = BINDING.get();
        TransactionContext context = binding == null ? null : binding.get();
        if (context == null) {
            return null;
        }
        // a context that completed is no longer usable — e.g. an inherited one whose transaction
        // has already finished — and must not be handed back as the current context
        return context.isCompleted() ? null : context;
    }

    /**
     * Whether any context (including {@link TransactionContext#NON_TRANSACTIONAL}) is bound.
     *
     * @return {@code true} if a context is bound to the current thread
     */
    public static boolean isBound() {
        return get() != null;
    }

    /**
     * Binds the given context for the duration of the action, restoring the previous context afterwards.
     * Nested calls shadow and then restore the outer context.
     *
     * <p>This method is the primary way to activate a transaction context. It ensures proper
     * cleanup even if the action throws an exception.</p>
     *
     * @param <T> the return type of the action
     * @param context the transaction context to bind
     * @param action the action to execute with the context bound
     * @return the result of the action
     * @throws Exception if the action throws an exception
     */
    public static <T> T call(TransactionContext context, Callable<T> action) throws Exception {
        ContextBinding previous = BINDING.get();
        ContextBinding binding = new ContextBinding(context);
        BINDING.set(binding);
        try {
            return action.call();
        } finally {
            // close the handle before restoring the previous one so that every thread that
            // inherited this scope stops seeing the context once it completes
            binding.close();
            if (previous == null) {
                BINDING.remove();
            } else {
                BINDING.set(previous);
            }
        }
    }
}
