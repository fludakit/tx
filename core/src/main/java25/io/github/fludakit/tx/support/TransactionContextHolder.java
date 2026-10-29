package io.github.fludakit.tx.support;

import java.util.concurrent.Callable;

/**
 * Holds the current {@link TransactionContext} for the calling thread/scope.
 *
 * <p>JDK 25+ implementation. {@code ScopedValue} is the primary carrier (so the state propagates
 * to {@code StructuredTaskScope} subtasks). There is deliberately no {@link InheritableThreadLocal}
 * fallback in this revision: a context is only visible where it was placed by {@link #call} or
 * inherited through a live structured-concurrency scope, never through raw thread inheritance.</p>
 *
 * <p>The JDK 21 base implementation carries the context through an {@link InheritableThreadLocal}
 * that wraps a {@link ContextBinding}; the JDK 25 override carries it through a {@code ScopedValue}
 * that wraps the same {@link ContextBinding}.</p>
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
 * <h2>Lifetime of a bound context</h2>
 * <p>A context bound through {@link #call} is not usable once its transaction completed; such
 * contexts are treated as unbound when read through {@link #get}.</p>
 *
 * <h2>Multi-Release JAR</h2>
 * <p>JDK 25 override of the holder that carries the context through a {@code ScopedValue}; the JDK
 * 21 base implementation (with the {@link InheritableThreadLocal} fallback) remains for older
 * runtimes.</p>
 *
 * @see TransactionContext
 * @see io.github.fludakit.tx.TransactionManager
 */
public final class TransactionContextHolder {

    private static final ScopedValue<ContextBinding> CURRENT = ScopedValue.newInstance();

    private TransactionContextHolder() {
    }

    /**
     * Returns the bound context, or {@code null} if none is bound.
     */
    public static TransactionContext get() {
        ContextBinding binding = CURRENT.isBound() ? CURRENT.get() : null;
        TransactionContext context = binding == null ? null : binding.get();
        if (context == null) {
            return null;
        }
        // a context that completed is no longer usable — e.g. one whose transaction has already
        // finished — and must not be handed back as the current context
        return context.isCompleted() ? null : context;
    }

    /**
     * Whether any context (including {@link TransactionContext#NON_TRANSACTIONAL}) is bound.
     */
    public static boolean isBound() {
        return get() != null;
    }

    /**
     * Binds the given context for the duration of the action, restoring the previous context afterwards.
     * Nested calls shadow and then restore the outer context.
     */
    public static <T> T call(TransactionContext context, Callable<T> action) throws Exception {
        return ScopedValue.where(CURRENT, new ContextBinding(context)).call(action::call);
    }
}
