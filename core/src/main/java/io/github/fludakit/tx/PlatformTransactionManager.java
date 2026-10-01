package io.github.fludakit.tx;

import io.github.fludakit.tx.support.TransactionContext;

/**
 * Strategy SPI for resource-local transaction management.
 *
 * <p>An implementation drives the begin/commit/rollback of a single resource type (for example, a
 * JDBC {@code DataSource}). It does not interpret propagation rules: the
 * {@link io.github.fludakit.tx.cdi.TransactionalInterceptor}
 * resolves {@code TxType} and only asks the manager to {@link #getTransaction(TransactionDefinition) start}
 * a new transaction when one is needed.</p>
 *
 * <p>The {@link TransactionContext} returned by {@code getTransaction} is the single handle for the
 * transaction — it carries the bound resource, callbacks, and completion flags.</p>
 *
 * <h2>Implementation Contract</h2>
 * <p>Implementations must follow these rules:</p>
 * <ol>
 *   <li><b>Resource binding:</b> In {@link #getTransaction(TransactionDefinition)}, open the
 *       resource (Connection, EntityManager, etc.) and bind it to the returned
 *       {@link TransactionContext} using {@link TransactionContext#bindResource(Object, Object)}.
 *       Use the resource owner (DataSource, EntityManagerFactory) as the key.</li>
 *   <li><b>Cleanup callback:</b> Register a {@link io.github.fludakit.tx.support.TransactionCallback}
 *       to release the resource in {@code afterCompletion}. This ensures cleanup even if the
 *       transaction fails.</li>
 *   <li><b>Commit:</b> In {@link #commit(TransactionContext)}, retrieve the bound resource and
 *       commit it. Throw {@link TransactionException} if commit fails.</li>
 *   <li><b>Rollback:</b> In {@link #rollback(TransactionContext)}, retrieve the bound resource and
 *       roll it back. Check if the transaction is still active before rolling back.</li>
 * </ol>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * public class MyTransactionManager implements PlatformTransactionManager {
 *     private final DataSource dataSource;
 *     
 *     @Override
 *     public TransactionContext getTransaction(TransactionDefinition definition) {
 *         Connection conn = dataSource.getConnection();
 *         conn.setAutoCommit(false);
 *         
 *         TransactionContext context = new TransactionContext(true);
 *         context.bindResource(dataSource, conn);
 *         context.registerCallback(new TransactionCallback() {
 *             @Override
 *             public void afterCompletion(CompletionStatus status) {
 *                 try { conn.close(); } catch (SQLException e) { /* log *\/ }
 *             }
 *         });
 *         return context;
 *     }
 *     
 *     @Override
 *     public void commit(TransactionContext context) {
 *         Connection conn = context.getResource(dataSource, Connection.class);
 *         conn.commit();
 *     }
 *     
 *     @Override
 *     public void rollback(TransactionContext context) {
 *         Connection conn = context.getResource(dataSource, Connection.class);
 *         if (!conn.isClosed()) conn.rollback();
 *     }
 * }
 * }</pre>
 *
 * <h2>Built-in Implementations</h2>
 * <ul>
 *   <li>{@link io.github.fludakit.tx.jdbc.DataSourceTransactionManager} — JDBC resource-local
 *       transactions</li>
 *   <li>{@link io.github.fludakit.tx.jpa.JpaTransactionManager} — JPA resource-local transactions
 *       with JDBC fallback</li>
 * </ul>
 *
 * @see TransactionContext
 * @see io.github.fludakit.tx.support.TransactionCallback
 * @see io.github.fludakit.tx.jdbc.DataSourceTransactionManager
 * @see io.github.fludakit.tx.jpa.JpaTransactionManager
 */
public interface PlatformTransactionManager {

    /**
     * Begin a new resource-local transaction, bind its resource, and return the transaction context.
     *
     * <p>Implementations must:</p>
     * <ol>
     *   <li>Open the resource (Connection, EntityManager, etc.)</li>
     *   <li>Create a new {@link TransactionContext} with {@code actualTransactionActive = true}</li>
     *   <li>Bind the resource to the context using the resource owner as key</li>
     *   <li>Register a cleanup callback to release the resource</li>
     *   <li>Return the context</li>
     * </ol>
     *
     * @param definition transaction attributes (propagation, rollback rules)
     * @return the transaction context for the started transaction
     * @throws TransactionException if the transaction could not be started
     */
    TransactionContext getTransaction(TransactionDefinition definition) throws TransactionException;

    /**
     * Commit the transaction represented by the given context.
     *
     * <p>Implementations must retrieve the bound resource from the context and commit it. If the
     * commit fails, throw a {@link TransactionException}.</p>
     *
     * @param context the transaction context returned by {@link #getTransaction(TransactionDefinition)}
     * @throws TransactionException if the commit fails
     */
    void commit(TransactionContext context) throws TransactionException;

    /**
     * Roll back the transaction represented by the given context.
     *
     * <p>Implementations must retrieve the bound resource from the context and roll it back. Check
     * if the transaction is still active before rolling back to avoid exceptions. If the rollback
     * fails, throw a {@link TransactionException}.</p>
     *
     * @param context the transaction context returned by {@link #getTransaction(TransactionDefinition)}
     * @throws TransactionException if the rollback fails
     */
    void rollback(TransactionContext context) throws TransactionException;
}
