package io.github.fludakit.tx;

/**
 * A participant that is notified at the lifecycle points of the transaction it is registered on.
 *
 * <h2>Order</h2>
 * <p>On commit: {@link #beforeCommit(boolean)}, {@link #beforeCompletion()},
 * {@link #afterCommit()}, {@link #afterCompletion(Status)}. On rollback:
 * {@link #beforeCompletion()}, {@link #afterCompletion(Status)}.</p>
 *
 * <h2>Registration</h2>
 * <p>Synchronizations are registered on a {@link TransactionStatus} with
 * {@link TransactionStatus#registerSynchronization(TransactionSynchronization)}. A
 * synchronization registered during a participating ({@code REQUIRED}) or nested call is
 * attached to the transaction that is actually running, so it fires exactly once — at the real
 * boundary.</p>
 *
 * <p>Callbacks run on the thread that completes the transaction and must not throw: a failure in
 * {@code beforeCommit} aborts the commit.</p>
 *
 * @see TransactionStatus#registerSynchronization(TransactionSynchronization)
 */
public interface TransactionSynchronization {

    /**
     * Invoked before the transaction commits — the last chance to flush, validate, or veto.
     *
     * @param readOnly whether the transaction was defined as read-only
     */
    default void beforeCommit(boolean readOnly) {
    }

    /** Invoked before the transaction completes, whether it commits or rolls back. */
    default void beforeCompletion() {
    }

    /** Invoked after a successful commit. */
    default void afterCommit() {
    }

    /**
     * Invoked after the transaction completed, whatever the outcome — the place for resource
     * release.
     *
     * @param status the completion status
     */
    default void afterCompletion(Status status) {
    }

    /** How the transaction ended. */
    enum Status {

        /** The transaction committed. */
        COMMITTED,

        /** The transaction rolled back. */
        ROLLED_BACK,

        /** Completion was attempted but the outcome could not be determined. */
        UNKNOWN
    }
}
