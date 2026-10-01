/**
 * Resource-local transaction management for Jakarta EE and Java SE applications.
 *
 * <p>This package provides a lightweight transaction management SPI and support classes for
 * managing resource-local transactions (JDBC, JPA) without requiring a full JTA implementation.
 * The main entry point is {@link io.github.fludakit.tx.PlatformTransactionManager}, which
 * defines the contract for starting, committing, and rolling back transactions.</p>
 *
 * <h2>Core Concepts</h2>
 * <ul>
 *   <li>{@link io.github.fludakit.tx.PlatformTransactionManager} — SPI for transaction lifecycle management</li>
 *   <li>{@link io.github.fludakit.tx.TransactionDefinition} — Transaction propagation and isolation settings</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionContext} — Holds bound resources and callbacks for a transaction</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionCallback} — Lifecycle hooks (beforeCommit, afterCompletion, etc.)</li>
 *   <li>{@link io.github.fludakit.tx.support.TransactionalOperations} — Programmatic transaction execution</li>
 * </ul>
 *
 * <h2>Usage with CDI</h2>
 * <p>In Jakarta EE environments, use the {@code @Transactional} annotation to declaratively
 * manage transactions. The CDI interceptor handles propagation and delegates to the appropriate
 * {@code PlatformTransactionManager}:</p>
 * <pre>{@code
 * @ApplicationScoped
 * public class UserService {
 *     @Inject
 *     private EntityManager em;
 *
 *     @Transactional
 *     public void createUser(User user) {
 *         em.persist(user);
 *     }
 * }
 * }</pre>
 *
 * <h2>Programmatic Usage</h2>
 * <p>For programmatic control, inject {@link io.github.fludakit.tx.support.TransactionalOperations}:</p>
 * <pre>{@code
 * @Inject
 * private TransactionalOperations txOps;
 *
 * public void transferMoney(Long fromId, Long toId, BigDecimal amount) {
 *     txOps.execute(ctx -> {
 *         Connection conn = ctx.getResource(dataSource, Connection.class);
 *         // ... perform transfer ...
 *         return null;
 *     });
 * }
 * }</pre>
 *
 * <h2>Built-in Implementations</h2>
 * <ul>
 *   <li>{@code io.github.fludakit.tx.jdbc.DataSourceTransactionManager} — JDBC transactions</li>
 *   <li>{@code io.github.fludakit.tx.jpa.JpaTransactionManager} — JPA transactions with JDBC fallback</li>
 * </ul>
 *
 * <h2>Transaction Propagation</h2>
 * <p>The {@link io.github.fludakit.tx.TransactionDefinition} supports standard propagation types:</p>
 * <ul>
 *   <li>{@code REQUIRED} (default) — Join existing transaction or create new one</li>
 *   <li>{@code REQUIRES_NEW} — Always create new transaction, suspending existing</li>
 *   <li>{@code SUPPORTS} — Execute within transaction if one exists</li>
 *   <li>{@code NOT_SUPPORTED} — Execute non-transactionally, suspending existing</li>
 *   <li>{@code MANDATORY} — Require existing transaction</li>
 *   <li>{@code NEVER} — Fail if transaction exists</li>
 * </ul>
 *
 * <h2>Resource Binding</h2>
 * <p>Each {@code PlatformTransactionManager} binds its resource (Connection, EntityManager)
 * to the {@link io.github.fludakit.tx.support.TransactionContext}. Use
 * {@link io.github.fludakit.tx.support.TransactionContext#getResource(Object, Class)} to retrieve
 * bound resources in a type-safe manner.</p>
 *
 * @see io.github.fludakit.tx.PlatformTransactionManager
 * @see io.github.fludakit.tx.TransactionDefinition
 * @see io.github.fludakit.tx.support.TransactionContext
 * @see io.github.fludakit.tx.support.TransactionalOperations
 */
package io.github.fludakit.tx;
