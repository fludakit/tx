package io.github.fludakit.tx;

/**
 * A unit of work executed inside a transaction boundary, receiving the transaction's backing
 * resource directly — the aware {@code DataSource} for JDBC operations, the bound
 * {@code EntityManager} for JPA — together with the transaction's {@link TransactionStatus}.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * txManager.execute((em, status) -> {
 *     em.persist(entity);
 *     return entity.getId();
 * });
 * }</pre>
 *
 * <h2>Exception Handling</h2>
 * <p>The failure escaping this method is propagated unchanged, and the transaction's
 * {@link TransactionDefinition#rollbackOn(Throwable)} rules decide whether the transaction
 * commits or rolls back: a {@link RuntimeException} or {@link Error} rolls back, a checked
 * exception commits unless the definition says otherwise. Outside an actual transaction the
 * resource is {@code null}.</p>
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
     * @param resource the resource the surrounding transaction is bound to, or {@code null}
     *                 outside an actual transaction ({@code SUPPORTS}, {@code NOT_SUPPORTED},
     *                 {@code NEVER})
     * @param status   the transaction's status: completion state, rollback-only flag, and — when
     *                 the manager supports them — savepoints
     * @return the result of the unit of work
     * @throws Exception if the unit of work fails
     */
    R call(T resource, TransactionStatus<T> status) throws Exception;
}
