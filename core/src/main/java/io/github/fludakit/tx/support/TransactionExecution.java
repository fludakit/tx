package io.github.fludakit.tx.support;

/**
 * A unit of work executed inside a transaction boundary, receiving the transaction's backing
 * resource directly — the aware {@code DataSource} for JDBC operations, the bound
 * {@code EntityManager} for JPA — together with the transaction's {@link TransactionStatus}.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * txOps.execute((em, status) -> {
 *     em.persist(entity);
 *     return entity.getId();
 * });
 * }</pre>
 *
 * <h2>Exception Handling</h2>
 * <p>If the callback throws a {@link RuntimeException} or {@code Error}, the transaction is
 * rolled back. Checked exceptions propagate to the caller and also trigger rollback.</p>
 *
 * @param <T> the backing resource type handed to the unit of work
 * @param <R> the return type of the unit of work
 * @see TransactionalOperations
 */
@FunctionalInterface
public interface TransactionExecution<T, R> {

    /**
     * Executes the unit of work with the transaction's backing resource and status.
     *
     * @param resource the resource the surrounding transaction is bound to (may be a
     *                 non-transactional view or {@code null} outside an active transaction,
     *                 depending on the manager)
     * @param status   the transaction's status: completion state, rollback-only flag, and — when
     *                 the manager supports them — savepoints
     * @return the result of the unit of work
     * @throws Exception if the unit of work fails
     */
    R call(T resource, TransactionStatus status) throws Exception;
}
