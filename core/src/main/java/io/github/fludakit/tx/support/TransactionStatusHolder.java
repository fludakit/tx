package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionStatus;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Holds the current {@link TransactionStatus} for the calling thread or scope.
 *
 * <p>This is the base (JDK 21) implementation, backed by an {@link InheritableThreadLocal}
 * carrying a revocable {@link StatusBinding}. On JDK 25+ a multi-release overlay carries the
 * same binding through a {@code ScopedValue}, so the status also propagates to structured
 * concurrency subtasks.</p>
 *
 * <h2>Binding a transaction</h2>
 * <pre>{@code
 * return TransactionStatusHolder.call(status, () -> {
 *     // the status is ambient here: lookups and savepoint creation see it
 *     return work.call(status.getResource(), status);
 * }); // the previous status (or nothing) is restored here
 * }</pre>
 *
 * <p>Nested calls shadow and then restore the outer status, which is how suspension works:
 * {@code REQUIRES_NEW} and {@code NOT_SUPPORTED} bind an inner status and the outer one comes
 * back when the inner scope closes. No explicit push/pop bookkeeping exists, so an inner scope
 * cannot leak.</p>
 *
 * <h2>Lifetime</h2>
 * <p>The binding is closed when the scope ends, which revokes it for every thread that
 * inherited it: a thread created inside a transaction that outlives the transaction sees no
 * status at all instead of a stale one.</p>
 *
 * <p>{@link #get()} returns the status for the whole binding scope, including the completion
 * callbacks ({@code afterCommit}, {@code afterCompletion}), because those callbacks still need
 * to reach the transaction's resource. A status that completed reports
 * {@link TransactionStatus#isCompleted()} {@code true}; it is not treated as an ambient
 * transaction by the manager.</p>
 *
 * @see AbstractTransactionOperations
 * @see TransactionStatus
 */
public final class TransactionStatusHolder {

    private static final InheritableThreadLocal<StatusBinding<?>> BINDING = new InheritableThreadLocal<>();

    private TransactionStatusHolder() {
    }

    /**
     * The status bound to the current thread or scope.
     *
     * @return the bound status, or {@code null} when no status is bound
     */
    public static TransactionStatus<?> get() {
        StatusBinding<?> binding = BINDING.get();
        return binding == null ? null : binding.get();
    }

    /**
     * Whether a status — including the no-transaction status — is bound.
     *
     * @return {@code true} if a status is bound here
     */
    public static boolean isBound() {
        return get() != null;
    }

    /**
     * Binds the given status for the duration of the action, restoring the previous status
     * afterwards.
     *
     * @param status the status to bind
     * @param action the action to run with the status bound
     * @param <R>    the result type
     * @return the result of the action
     * @throws Exception if the action fails
     */
    public static <R> R call(TransactionStatus<?> status, Callable<R> action) throws Exception {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(action, "action must not be null");

        StatusBinding<?> previous = BINDING.get();
        StatusBinding<?> binding = new StatusBinding<>(status);
        BINDING.set(binding);
        try {
            return action.call();
        } finally {
            // revoke first: threads that inherited this binding must stop seeing the status even
            // before the parent restores its own binding
            binding.close();
            if (previous == null) {
                BINDING.remove();
            } else {
                BINDING.set(previous);
            }
        }
    }
}
