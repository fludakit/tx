/**
 * Runtime support and SPI plumbing for {@code fluda-tx-core}.
 *
 * <p>These types carry a transaction between the application-facing API in
 * {@code io.github.fludakit.tx} and the {@link io.github.fludakit.tx.TransactionManager}
 * implementations. Applications rarely reference this package directly; transaction managers
 * and integrations (CDI, JDBC, JPA) build on it.</p>
 *
 * <h2>What Lives Here</h2>
 * <ul>
 *   <li>{@link io.github.fludakit.tx.support.TransactionContext} — the per-transaction handle:
 *       bound resources, callbacks, and status</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionContextHolder} — thread/scope binding of
 *       the active context, plus the non-transactional sentinel</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionStatus} — read-only, rollback-only, and
 *       completion state, plus the savepoint operations of the transaction</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionCallback} — lifecycle hooks
 *       ({@code beforeCommit}, {@code afterCommit}, {@code afterCompletion})</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionSavepointManager} — the savepoint SPI a
 *       manager implements when it can create savepoints</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionTemplate} — the single execution template
 *       owning propagation dispatch and the begin/complete/commit/rollback lifecycle; both
 *       front-ends delegate to it</li>
 *   <li>{@link io.github.fludakit.tx.support.RollbackDecisionStrategy} — the pluggable strategy
 *       deciding whether a failed execution rolls back</li>
 *   <li>{@link io.github.fludakit.tx.support.CallbackRegistrar} — the hook front-ends use to
 *       register their own callbacks on a newly begun transaction</li>
 *   <li>{@link io.github.fludakit.tx.support.DefaultTransactionalOperations} — the default
 *       {@link io.github.fludakit.tx.TransactionalOperations} implementation</li>
 * </ul>
 *
 * @see io.github.fludakit.tx.TransactionManager
 * @see io.github.fludakit.tx.TransactionalOperations
 * @see io.github.fludakit.tx.support.TransactionContext
 * @see io.github.fludakit.tx.support.TransactionStatus
 */
package io.github.fludakit.tx.support;
