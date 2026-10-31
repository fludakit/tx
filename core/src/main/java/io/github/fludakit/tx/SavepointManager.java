package io.github.fludakit.tx;

/**
 * Savepoint operations over one active transaction.
 *
 * <p>A transaction manager that supports savepoints supplies a manager for each transaction it
 * begins; {@link TransactionStatus#isSavepointSupported()} then reports {@code true} and the
 * status exposes savepoint creation, rollback-to, and release. Implementations wrap their native
 * construct — a JDBC {@link java.sql.Savepoint}, or a provider equivalent.</p>
 *
 * <p>Savepoints exist for one purpose in this model: {@code NESTED} propagation. The default
 * nested hooks are expressed entirely in terms of this interface, so a manager that supplies a
 * manager gets nested transactions for free. Direct use is for advanced cases only.</p>
 *
 * @see TransactionStatus#createSavepoint()
 * @see jakarta.transaction.Transactional.TxType#NESTED
 */
public interface SavepointManager {

    /**
     * Creates a savepoint at the transaction's current point.
     *
     * @return the savepoint handle
     * @throws NestedTransactionNotSupportedException if the underlying resource does not support
     *                                                savepoints
     */
    TransactionSavepoint createSavepoint();

    /**
     * Undoes every change made after the savepoint was created; the transaction stays active.
     *
     * @param savepoint the handle returned by {@link #createSavepoint()}
     */
    void rollbackToSavepoint(TransactionSavepoint savepoint);

    /**
     * Releases the savepoint: its changes become part of the surrounding transaction.
     *
     * @param savepoint the handle returned by {@link #createSavepoint()}
     */
    void releaseSavepoint(TransactionSavepoint savepoint);
}
