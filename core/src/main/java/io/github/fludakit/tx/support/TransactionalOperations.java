package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;

/**
 * Operations for executing code within transaction boundaries.
 *
 * <p>This interface provides a programmatic way to manage transactions, complementing the
 * declarative {@code @Transactional} annotation. It wraps a unit of work with transaction
 * lifecycle management (begin, commit, rollback).</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * @Inject TransactionalOperations txOps;
 * 
 * public void createUser(User user) {
 *     txOps.execute(ctx -> {
 *         EntityManager em = JpaTransactionManager.currentEntityManager();
 *         em.persist(user);
 *         
 *         // Can also use JDBC in the same transaction
 *         Connection conn = (Connection) ctx.getResource(dataSource);
 *         // ... JDBC operations ...
 *         
 *         return user.getId();
 *     });
 * }
 * }</pre>
 *
 * <h2>Transaction Propagation</h2>
 * <p>The {@code execute} methods respect the {@link TransactionDefinition} propagation settings:</p>
 * <ul>
 *   <li>{@code REQUIRED} (default): Join existing transaction or create new one</li>
 *   <li>{@code REQUIRES_NEW}: Always create a new transaction, suspending any existing one</li>
 *   <li>{@code SUPPORTS}: Execute within existing transaction, or non-transactionally if none exists</li>
 *   <li>Other propagation types are also supported</li>
 * </ul>
 *
 * <h2>Exception Handling</h2>
 * <p>If the callback throws a {@link RuntimeException} or {@link Error}, the transaction is
 * automatically rolled back. Checked exceptions must be handled within the callback or wrapped
 * in a RuntimeException.</p>
 *
 * @see TransactionCallback
 * @see TransactionContext
 */
public interface TransactionalOperations {

    /**
     * Execute the given callback within a transaction boundary.
     *
     * <p>The transaction lifecycle is managed automatically:</p>
     * <ol>
     *   <li>Begin transaction (according to propagation rules)</li>
     *   <li>Execute callback</li>
     *   <li>Commit if successful, rollback if exception thrown</li>
     * </ol>
     *
     * @param <T> the return type
     * @param definition transaction definition (propagation, isolation, timeout, etc.)
     * @param callback the code to execute within the transaction
     * @return the result of the callback
     * @throws Exception if the callback throws an exception
     */
    <T> T execute(TransactionDefinition definition, TransactionCallback<T> callback) throws Exception;

    /**
     * Execute the given callback within a transaction using default settings.
     *
     * <p>Equivalent to calling {@code execute(TransactionDefinition.withDefaults(), callback)}.</p>
     *
     * @param <T> the return type
     * @param callback the code to execute within the transaction
     * @return the result of the callback
     * @throws Exception if the callback throws an exception
     */
    default <T> T execute(TransactionCallback<T> callback) throws Exception {
        return execute(TransactionDefinition.withDefaults(), callback);
    }

    /**
     * Execute a read-only operation within a transaction.
     *
     * <p>This is a convenience method that sets the transaction to read-only mode, which may
     * enable optimizations in some transaction managers.</p>
     *
     * @param <T> the return type
     * @param callback the code to execute within the transaction
     * @return the result of the callback
     * @throws Exception if the callback throws an exception
     */
    default <T> T executeRead(TransactionCallback<T> callback) throws Exception {
        return execute(TransactionDefinition.readOnlyDefinition(), callback);
    }

    /**
     * Execute a write operation within a transaction.
     *
     * <p>This is a convenience method for write operations. It uses default transaction settings.</p>
     *
     * @param <T> the return type
     * @param callback the code to execute within the transaction
     * @return the result of the callback
     * @throws Exception if the callback throws an exception
     */
    default <T> T executeWrite(TransactionCallback<T> callback) throws Exception {
        return execute(TransactionDefinition.withDefaults(), callback);
    }
}
