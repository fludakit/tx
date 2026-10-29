package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionSavepoint;

import java.util.Objects;

/**
 * The status facts of one transaction: read-only hint, whether it is a new (vs joined)
 * transaction, its rollback-only flag, and its completion state.
 *
 * <p>Carried by {@link TransactionContext} (via {@link TransactionContext#getStatus()}) and
 * mutated through the context — the context's non-transactional sentinel rejects all mutations,
 * including status ones.</p>
 *
 * <p>When the underlying {@link io.github.fludakit.tx.TransactionManager} supports savepoints,
 * it attaches a {@link TransactionSavepointManager} and the status exposes
 * {@link #createSavepoint()}/{@link #rollbackToSavepoint(TransactionSavepoint)}/
 * {@link #releaseSavepoint(TransactionSavepoint)} — the handles a nested section works with.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TransactionStatus status = ctx.getStatus();
 * if (status.isRollbackOnly()) { ... }
 * if (status.isCompleted()) { ... }
 * }</pre>
 */
public final class TransactionStatus {

    /** The completion state of a transaction. */
    public enum State {
        /** Begun, not yet completed. */
        ACTIVE,
        /** Committed successfully. */
        COMMITTED,
        /** Rolled back (explicitly, on failure, or via rollback-only). */
        ROLLED_BACK,
        /** Completion attempted but the outcome could not be determined. */
        UNKNOWN
    }

    private final boolean readOnly;
    private final boolean newTransaction;
    private final TransactionSavepointManager savepointManager;
    private volatile State state = State.ACTIVE;
    private volatile boolean rollbackOnly;

    private TransactionStatus(boolean readOnly, boolean newTransaction, TransactionSavepointManager savepointManager) {
        this.readOnly = readOnly;
        this.newTransaction = newTransaction;
        this.savepointManager = savepointManager;
    }

    /**
     * The initial status for a transaction started with the given definition: a new transaction
     * carrying the definition's read-only hint, with savepoint support when the manager
     * supplies it.
     */
    public static TransactionStatus initial(TransactionDefinition definition, TransactionSavepointManager savepointManager) {
        Objects.requireNonNull(definition, "definition must not be null");
        return new TransactionStatus(definition.readOnly(), true, savepointManager);
    }

    /** The default status: a new transaction without the read-only hint and without savepoints. */
    public static TransactionStatus initial() {
        return new TransactionStatus(false, true, null);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** Whether this status belongs to a transaction begun here (not joined). */
    public boolean isNewTransaction() {
        return newTransaction;
    }

    public State getState() {
        return state;
    }

    public boolean isCompleted() {
        return state != State.ACTIVE;
    }

    /** Transitions the transaction out of {@link State#ACTIVE} into the given terminal state. */
    void markCompleted(State state) {
        Objects.requireNonNull(state, "state must not be null");
        if (state == State.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE is not a completion state");
        }
        if (this.state != State.ACTIVE) {
            throw new IllegalStateException(
                    "Transaction is already completed - do not call commit or rollback more than once per transaction");
        }
        this.state = state;
    }

    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }

    /** Whether the underlying manager attached savepoint support for this transaction. */
    public boolean isSavepointSupported() {
        return savepointManager != null;
    }

    /**
     * Creates a savepoint at the transaction's current point.
     *
     * @throws UnsupportedOperationException if the manager attached no savepoint support
     */
    public TransactionSavepoint createSavepoint() {
        requireSavepoints();
        return savepointManager.createSavepoint();
    }

    /**
     * Undoes every change made after the savepoint was created; the transaction stays active.
     *
     * @throws UnsupportedOperationException if the manager attached no savepoint support
     */
    public void rollbackToSavepoint(TransactionSavepoint savepoint) {
        requireSavepoints();
        savepointManager.rollbackToSavepoint(savepoint);
    }

    /**
     * Releases the savepoint: its changes become permanent parts of the transaction.
     *
     * @throws UnsupportedOperationException if the manager attached no savepoint support
     */
    public void releaseSavepoint(TransactionSavepoint savepoint) {
        requireSavepoints();
        savepointManager.releaseSavepoint(savepoint);
    }

    private void requireSavepoints() {
        if (savepointManager == null) {
            throw new UnsupportedOperationException(
                    "This transaction has no savepoint support (manager did not attach a "
                            + "TransactionSavepointManager)");
        }
    }
}
