package io.github.fludakit.tx.support;

/**
 * A callback for executing code within a transaction boundary.
 *
 * <p>This functional interface is used with {@link TransactionalOperations#execute} to wrap
 * a unit of work with transaction management.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * txOps.execute(ctx -> {
 *     EntityManager em = JpaTransactionManager.currentEntityManager();
 *     em.persist(entity);
 *     return entity.getId();
 * });
 * }</pre>
 *
 * <h2>Exception Handling</h2>
 * <p>If the callback throws a {@link RuntimeException} or {@link Error}, the transaction is
 * automatically rolled back. Checked exceptions are wrapped and will also trigger rollback.</p>
 *
 * @param <T> the return type of the callback
 * @see TransactionalOperations
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Execute the callback within the given transaction context.
     *
     * @param context the transaction context providing access to bound resources
     * @return the result of the callback
     * @throws Exception if the operation fails
     */
    T call(TransactionContext context) throws Exception;
}
