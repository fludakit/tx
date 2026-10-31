package io.github.fludakit.tx;

/**
 * A named point inside an active transaction that later changes can be rolled back to without
 * ending the transaction — the handle returned by {@link TransactionStatus#createSavepoint()}.
 *
 * <p>Opaque by design: implementations wrap their native construct (a JDBC {@code Savepoint},
 * a provider equivalent). Savepoints are strictly scoped to the executing section that created
 * them; on success the section releases the savepoint, on failure it rolls back to it and the
 * surrounding transaction continues.</p>
 *
 * @see TransactionStatus#createSavepoint()
 * @see SavepointManager
 */
public interface TransactionSavepoint {

    /** The implementation-assigned name of this savepoint. */
    String name();
}
