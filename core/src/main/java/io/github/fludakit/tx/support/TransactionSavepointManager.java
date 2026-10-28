package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionSavepoint;

/**
 * Savepoint operations over one active transaction, attached to its
 * {@link TransactionStatus} by a {@link io.github.fludakit.tx.TransactionManager} that
 * supports savepoints — reached from inside transactions via
 * {@link TransactionStatus#createSavepoint()}.
 *
 * <p>Implementations wrap their native construct (a JDBC {@code Savepoint}, a provider
 * equivalent). A manager without savepoint support attaches nothing and
 * {@link TransactionStatus#isSavepointSupported()} reports {@code false}.</p>
 */
public interface TransactionSavepointManager {

    /** Creates a savepoint at the transaction's current point. */
    TransactionSavepoint createSavepoint();

    /** Undoes every change made after the savepoint was created; the transaction stays active. */
    void rollbackToSavepoint(TransactionSavepoint savepoint);

    /** Releases the savepoint: its changes become permanent parts of the transaction. */
    void releaseSavepoint(TransactionSavepoint savepoint);
}
