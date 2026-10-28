package io.github.fludakit.tx;

/**
 * Operations for executing code within transaction boundaries, parameterized by the backing
 * resource bound to the transaction.
 *
 * <p>This interface provides a programmatic way to manage transactions, complementing the
 * declarative {@code @Transactional} annotation. The execute callback receives the transaction's
 * backing resource directly — no context lookup at the usage site:</p>
 *
 * <pre>{@code
 * &#64;Inject TransactionalOperations&lt;EntityManager&gt; jpaOps;
 *
 * public void createUser(User user) {
 *     jpaOps.execute((em, status) -> {
 *         em.persist(user);
 *         return user.getId();
 *     });
 * }
 * }</pre>
 *
 * <p>The type parameter follows the {@link TransactionManager} the
 * operations are built from: {@code DataSource} for the JDBC manager (an aware view whose
 * {@code getConnection()} joins the active transaction), {@code EntityManager} for the JPA
 * manager.</p>
 *
 * <h2>Transaction Propagation</h2>
 * <p>The {@code execute} methods respect the {@link TransactionDefinition} propagation settings:
 * {@code REQUIRED} (default) joins or creates, {@code REQUIRES_NEW} suspends, {@code SUPPORTS},
 * {@code NOT_SUPPORTED}, {@code MANDATORY}, and {@code NEVER} behave per their semantics.</p>
 *
 * @param <T> the backing resource type handed to the execute callback
 * @see TransactionExecution
 * @see TransactionManager
 */
public interface TransactionalOperations<T> {

    /**
     * Execute the given unit of work within a transaction boundary, handing it the transaction's
     * backing resource.
     *
     * <p>The transaction lifecycle is managed automatically: begin (per propagation), execute,
     * commit on success, rollback on exception.</p>
     *
     * @param <R>        the return type
     * @param definition transaction definition (propagation, rollback rules, readOnly)
     * @param execution  the unit of work, receiving the backing resource
     * @return the result of the unit of work
     * @throws Exception if the unit of work throws an exception
     */
    <R> R execute(TransactionDefinition definition, TransactionExecution<T, R> execution) throws Exception;

    /**
     * Execute the given unit of work within a transaction using default settings.
     *
     * <p>Equivalent to calling {@code execute(TransactionDefinition.withDefaults(), execution)}.</p>
     */
    default <R> R execute(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.withDefaults(), execution);
    }

    /**
     * Execute a read-only operation within a transaction — the definition carries the read-only
     * hint, which transaction managers may apply.
     */
    default <R> R executeRead(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.readOnlyDefinition(), execution);
    }

    /**
     * Execute a write operation within a transaction using default settings.
     */
    default <R> R executeWrite(TransactionExecution<T, R> execution) throws Exception {
        return execute(TransactionDefinition.withDefaults(), execution);
    }

    /**
     * Execute a nested unit of work inside the active transaction, scoped by a savepoint: on
     * success the savepoint is released and the changes become part of the surrounding
     * transaction; on failure the work after the savepoint is undone and the exception
     * propagates — <b>without</b> marking the surrounding transaction rollback-only, so the
     * caller can catch and continue.
     *
     * <p>Outside an active transaction this behaves like {@link #execute(TransactionExecution)}
     * (a full transaction is begun, there is nothing to nest into). Requires a
     * {@link TransactionManager} with savepoint support.</p>
     */
    default <R> R executeNested(TransactionExecution<T, R> execution) throws Exception {
        throw new UnsupportedOperationException("executeNested requires a DefaultTransactionalOperations");
    }
}
