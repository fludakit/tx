package io.github.fludakit.tx;

import java.util.Optional;

/**
 * Programmatic transaction boundaries over one resource type.
 *
 * <p>This is the application-facing half of a {@link TransactionManager}: it executes a unit of
 * work inside a transaction boundary that follows the given definition, and exposes the ambient
 * transaction for read access.</p>
 *
 * <pre>{@code
 * @Inject TransactionalOperations<EntityManager> txOps;
 *
 * public Long createUser(User user) throws Exception {
 *     return txOps.execute((em, status) -> {
 *         em.persist(user);
 *         return user.getId();
 *     });
 * }
 * }</pre>
 *
 * <p>{@code execute} declares {@code throws Exception} because the unit of work may; the
 * transaction commits or rolls back according to the definition's rollback rules and the failure
 * is propagated unchanged.</p>
 *
 * @param <T> the resource type the manager binds
 * @see TransactionManager
 * @see TransactionStatus
 */
public interface TransactionalOperations<T> {

    /**
     * Executes a unit of work inside a transaction boundary.
     *
     * <p>Propagation follows {@link TransactionDefinition#propagation()}: the work joins the
     * ambient transaction, runs without one, or starts one. When this call owns the transaction,
     * it commits on normal completion and rolls back when the definition's rules match the
     * escaping failure or the transaction was marked rollback-only.</p>
     *
     * @param definition the transaction attributes
     * @param execution  the unit of work
     * @param <R>        the result type
     * @return the result of the unit of work
     * @throws Exception the failure thrown by the unit of work, unchanged
     */
    <R> R execute(TransactionDefinition definition, TransactionExecution<T, R> execution) throws Exception;

    /** Executes a unit of work with {@link TransactionDefinition#withDefaults()}. */
    default <R> R execute(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.withDefaults(), execution);
    }

    /** Executes a unit of work with a read-only definition. */
    default <R> R executeRead(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.readOnlyDefinition(), execution);
    }

    /** Executes a unit of work with the default (writable) definition. */
    default <R> R executeWrite(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.withDefaults(), execution);
    }

    /**
     * Executes a unit of work in a savepoint-scoped section of the ambient transaction.
     *
     * <p>On success the savepoint is released and the work becomes part of the surrounding
     * transaction. On failure the work is rolled back to the savepoint and the failure
     * propagates, but the surrounding transaction stays usable and is <b>not</b> marked
     * rollback-only.</p>
     *
     * <p>Without an ambient transaction there is nothing to nest into, so a full transaction is
     * begun instead.</p>
     *
     * @param execution the unit of work
     * @param <R>       the result type
     * @return the result of the unit of work
     * @throws Exception the failure thrown by the unit of work, unchanged
     */
    default <R> R executeNested(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.withDefaults().withPropagation(Propagation.NESTED), execution);
    }

    /**
     * The ambient transaction, if an actual transaction is running on this thread/scope.
     *
     * @return the current status, or empty outside a transaction
     */
    Optional<TransactionStatus<T>> findTransactionStatus();

    /**
     * The resource bound to the ambient transaction, or {@code null} outside one.
     *
     * @return the current transaction's resource
     */
    T getResource();
}
