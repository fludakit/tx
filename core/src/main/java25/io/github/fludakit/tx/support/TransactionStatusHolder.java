package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionStatus;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Holds the current {@link TransactionStatus} for the calling thread or scope.
 *
 * <p>JDK 25+ implementation. {@code ScopedValue} is the carrier, so the status propagates to
 * {@code StructuredTaskScope} subtasks and nowhere else. There is deliberately no
 * {@link InheritableThreadLocal} fallback: a status is visible only where it was placed by
 * {@link #call}, never through raw thread inheritance.</p>
 *
 * <p>The binding is still closed when the scope ends, so a subtask that captured it cannot
 * observe the transaction after its boundary completed — the same guarantee the JDK 21 base
 * implementation provides.</p>
 *
 * @see AbstractTransactionOperations
 * @see TransactionStatus
 */
public final class TransactionStatusHolder {

    private static final ScopedValue<StatusBinding<?>> CURRENT = ScopedValue.newInstance();

    private TransactionStatusHolder() {
    }

    /**
     * The status bound to the current thread or scope.
     *
     * @return the bound status, or {@code null} when no status is bound
     */
    public static TransactionStatus<?> get() {
        return CURRENT.isBound() ? CURRENT.get().get() : null;
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

        StatusBinding<?> binding = new StatusBinding<>(status);
        try {
            return ScopedValue.where(CURRENT, binding).call(action::call);
        } finally {
            binding.close();
        }
    }
}
