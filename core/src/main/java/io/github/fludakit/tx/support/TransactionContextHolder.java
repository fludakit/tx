package io.github.fludakit.tx.support;

import java.util.concurrent.Callable;

/**
 * Holds the current {@link TransactionContext} for the calling thread/scope.
 *
 * <p>This is the base (JDK 21) implementation backed by an {@link InheritableThreadLocal}. On
 * JDK 25+ the multi-release override carries the state via {@code ScopedValue} so it also
 * propagates to structured-concurrency subtasks.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code TransactionContextHolder} provides thread-local storage for the current transaction
 * context. It is the single point of access for retrieving the active transaction context and
 * binding a context for the duration of a transactional operation.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // In PlatformTransactionManager.getTransaction()
 * TransactionContext context = new TransactionContext(true);
 * context.bindResource(dataSource, connection);
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
 * @see io.github.fludakit.tx.PlatformTransactionManager
 */
public final class TransactionContextHolder {

    private static final InheritableThreadLocal<TransactionContext> CURRENT = new InheritableThreadLocal<>();

    private TransactionContextHolder() {
    }

    /**
     * Returns the bound context, or {@code null} if none is bound.
     *
     * <p>This is the primary method for accessing the current transaction context. Callers should
     * always check for {@code null} before using the context.</p>
     *
     * @return the current transaction context, or {@code null} if no transaction is active
     */
    public static TransactionContext get() {
        return CURRENT.get();
    }

    /**
     * Whether any context (including {@link TransactionContext#NON_TRANSACTIONAL}) is bound.
     *
     * <p>This method returns {@code true} if a context is bound, even if it represents a
     * non-transactional state (e.g., for {@code NOT_SUPPORTED} propagation).</p>
     *
     * @return {@code true} if a context is bound to the current thread
     */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /**
     * Binds the given context for the duration of the action, restoring the previous context afterwards.
     * Nested calls shadow and then restore the outer context.
     *
     * <p>This method is the primary way to activate a transaction context. It ensures proper
     * cleanup even if the action throws an exception.</p>
     *
     * <h3>Nested Calls</h3>
     * <p>If a context is already bound when this method is called, the previous context is saved
     * and restored after the action completes. This supports nested transactions and transaction
     * suspension.</p>
     *
     * @param <T> the return type of the action
     * @param context the transaction context to bind
     * @param action the action to execute with the context bound
     * @return the result of the action
     * @throws Exception if the action throws an exception
     */
    public static <T> T call(TransactionContext context, Callable<T> action) throws Exception {
        TransactionContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
