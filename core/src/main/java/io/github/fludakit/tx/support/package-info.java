/**
 * Runtime support for the application-facing API in {@code io.github.fludakit.tx}.
 *
 * <p>Applications rarely reference this package directly; transaction managers extend the
 * template here and integrations (CDI, JDBC, JPA) build on it.</p>
 *
 * <h2>What lives here</h2>
 * <ul>
 *   <li>{@link io.github.fludakit.tx.support.AbstractTransactionOperations} — the template:
 *       propagation, suspension, begin/complete, and callback ordering. A manager supplies only
 *       {@code doBegin} / {@code doCommit} / {@code doRollback} and, optionally, savepoints</li>
 *   <li>{@link io.github.fludakit.tx.support.DefaultTransactionStatus} — the status
 *       implementation, in four kinds: new, joined, nested, and the shared no-transaction
 *       sentinel</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionStatusHolder} — the per-thread/scope
 *       binding of the current status (an {@code InheritableThreadLocal} on JDK 21, a
 *       {@code ScopedValue} on JDK 25+)</li>
 * </ul>
 *
 * @see io.github.fludakit.tx.TransactionStatus
 * @see io.github.fludakit.tx.TransactionManager
 * @see io.github.fludakit.tx.support.AbstractTransactionOperations
 */
package io.github.fludakit.tx.support;
