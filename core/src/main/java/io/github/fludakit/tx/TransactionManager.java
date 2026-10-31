package io.github.fludakit.tx;

/**
 * Resource-local transaction management for one resource type.
 *
 * <p>A manager is both the low-level demarcation API ({@link #getTransaction},
 * {@link #commit}, {@link #rollback}) and, through {@link TransactionalOperations}, the
 * programmatic boundary API. Applications normally inject the interface and call
 * {@code execute}; the declarative {@code @Transactional} front-end calls the same
 * {@code execute}. Both paths therefore share one lifecycle, one rollback rule set, and one
 * exception hierarchy.</p>
 *
 * <h2>Implementation contract</h2>
 * <p>Implementations extend {@link io.github.fludakit.tx.support.AbstractTransactionOperations}
 * and provide only resource primitives:</p>
 * <ul>
 *   <li>{@code doBegin}: acquire the resource, start the transaction, bind it to the status</li>
 *   <li>{@code doCommit} / {@code doRollback}: complete it</li>
 *   <li>{@link SavepointManager}: savepoints, which makes {@code NESTED} work</li>
 * </ul>
 *
 * <p>Propagation, suspension, lifecycle dispatch, synchronizations, and completion state are
 * owned by the template and must not be reimplemented by a manager.</p>
 *
 * <h2>Built-in implementations</h2>
 * <ul>
 *   <li>{@code JdbcTransactionManager} — {@code TransactionManager<Connection>}</li>
 *   <li>{@code JpaTransactionManager} — {@code TransactionManager<EntityManager>}</li>
 * </ul>
 *
 * @param <T> the resource type bound to a transaction
 * @see TransactionalOperations
 * @see TransactionStatus
 */
public interface TransactionManager<T> extends TransactionalOperations<T> {

    /**
     * Begins a transaction without binding it to the current scope — the manual-demarcation
     * entry point used together with {@link #commit(TransactionStatus)} /
     * {@link #rollback(TransactionStatus)}.
     *
     * <p>Propagation is applied as far as it can be without a scope: {@code SUPPORTS},
     * {@code NOT_SUPPORTED}, and {@code NEVER} yield the no-transaction status,
     * {@code MANDATORY} fails without an ambient transaction, and {@code NESTED} begins a
     * transaction when none is running. The caller owns the returned status and must complete
     * it exactly once.</p>
     *
     * @param definition the transaction attributes
     * @return the status of the begun (or joined) transaction
     * @throws TransactionException if the transaction cannot be started
     */
    TransactionStatus<T> getTransaction(TransactionDefinition definition) throws TransactionException;

    /**
     * Commits the transaction owned by the given status.
     *
     * @param status a status returned by {@link #getTransaction(TransactionDefinition)}
     * @throws TransactionException if the commit fails
     * @throws IllegalTransactionStateException if the status is already completed or is not owned
     *                                          by the caller
     * @throws UnexpectedRollbackException if the transaction was marked rollback-only by a
     *                                     participant
     */
    void commit(TransactionStatus<T> status) throws TransactionException;

    /**
     * Rolls back the transaction owned by the given status.
     *
     * @param status a status returned by {@link #getTransaction(TransactionDefinition)}
     * @throws TransactionException if the rollback fails
     * @throws IllegalTransactionStateException if the status is already completed or is not owned
     *                                          by the caller
     */
    void rollback(TransactionStatus<T> status) throws TransactionException;
}
