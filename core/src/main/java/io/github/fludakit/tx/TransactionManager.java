package io.github.fludakit.tx;

import io.github.fludakit.tx.support.TransactionContext;

/**
 * Strategy SPI for resource-local transaction management, parameterized by the backing
 * resource the transaction is bound to.
 *
 * <p>An implementation drives the begin/commit/rollback of a single resource type and hands
 * that resource to callers through {@link #getResource(TransactionContext)} — a JDBC manager
 * exposes an aware {@code DataSource} view (its {@code getConnection()} joins the active
 * transaction), a JPA manager exposes the bound {@code EntityManager}. The manager does not
 * interpret propagation rules: the {@link io.github.fludakit.tx.cdi.TransactionalInterceptor}
 * resolves {@code TxType} and only asks the manager to
 * {@link #getTransaction(TransactionDefinition) start} a new transaction when one is needed.</p>
 *
 * <p>The {@link TransactionContext} returned by {@code getTransaction} is the single handle for the
 * transaction — it carries the bound resources, callbacks, and completion flags.</p>
 *
 * <h2>Implementation Contract</h2>
 * <ol>
 *   <li><b>Resource binding:</b> in {@link #getTransaction(TransactionDefinition)}, open the
 *       resource and bind it to the returned {@link TransactionContext} by type —
 *       {@code Connection.class} for the physical connection, {@code EntityManager.class} for
 *       the JPA manager.</li>
 *   <li><b>Cleanup callback:</b> register a {@link io.github.fludakit.tx.support.TransactionCallback}
 *       to release the resource in {@code afterCompletion}, so cleanup happens even on failure.</li>
 *   <li><b>Commit / rollback:</b> retrieve the bound resource from the context and complete the
 *       transaction on it, throwing {@link TransactionException} on failure.</li>
 *   <li><b>Resource exposure:</b> {@link #getResource(TransactionContext)} returns the resource
 *       callers should work with — for a context without the bound resource (no active
 *       transaction), return the non-transactional view or {@code null}.</li>
 * </ol>
 *
 * <h2>Built-in Implementations</h2>
 * <ul>
 *   <li>{@link io.github.fludakit.tx.jdbc.JdbcTransactionManager} —
 *       {@code TransactionManager<DataSource>}</li>
 *   <li>{@link io.github.fludakit.tx.jpa.JpaTransactionManager} —
 *       {@code TransactionManager<EntityManager>}</li>
 * </ul>
 *
 * @param <T> the backing resource type callers receive inside a transaction
 * @see TransactionContext
 * @see TransactionalOperations
 */
public interface TransactionManager<T> {

    /**
     * Begin a new resource-local transaction, bind its resource, and return the transaction context.
     *
     * @param definition transaction attributes (propagation, rollback rules)
     * @return the transaction context for the started transaction
     * @throws TransactionException if the transaction could not be started
     */
    TransactionContext getTransaction(TransactionDefinition definition) throws TransactionException;

    /**
     * Commit the transaction represented by the given context.
     *
     * @param context the transaction context returned by {@link #getTransaction(TransactionDefinition)}
     * @throws TransactionException if the commit fails
     */
    void commit(TransactionContext context) throws TransactionException;

    /**
     * Roll back the transaction represented by the given context.
     *
     * @param context the transaction context returned by {@link #getTransaction(TransactionDefinition)}
     * @throws TransactionException if the rollback fails
     */
    void rollback(TransactionContext context) throws TransactionException;

    /**
     * The backing resource of the transaction bound to the current thread: an aware
     * {@code DataSource} view (JDBC) or the bound {@code EntityManager} (JPA). This is what
     * {@link TransactionalOperations} hands to the execute
     * callback. No context parameter — the ambient, thread-bound transaction is resolved
     * internally (outside any transaction, the non-transactional view or {@code null}).
     *
     * @return the backing resource of the current transaction
     */
    T getResource();
}
