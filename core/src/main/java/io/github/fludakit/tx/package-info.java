/**
 * Resource-local transaction management for Java SE and Jakarta EE applications — the
 * application-facing API.
 *
 * <p>These types describe transactions, not the containers they run in: a transaction is one
 * {@link io.github.fludakit.tx.TransactionStatus} object, created, bound, and completed by a
 * {@link io.github.fludakit.tx.TransactionManager}. There is no dependency on a JTA
 * implementation; only the standard {@code jakarta.transaction} annotations and exceptions are
 * reused.</p>
 *
 * <h2>The model</h2>
 * <ul>
 *   <li>{@link io.github.fludakit.tx.TransactionDefinition} — propagation, rollback rules, and
 *       the read-only hint; it owns the rollback decision</li>
 *   <li>{@link io.github.fludakit.tx.TransactionStatus} — one transaction: resource, rollback
 *       flags, synchronizations, savepoints, completion state</li>
 *   <li>{@link io.github.fludakit.tx.TransactionManager} — the demarcation API
 *       ({@code getTransaction} / {@code commit} / {@code rollback}) plus, through
 *       {@link io.github.fludakit.tx.TransactionalOperations}, {@code execute}</li>
 *   <li>{@link io.github.fludakit.tx.TransactionExecution} — the unit of work handed to
 *       {@code execute}, with the bound resource</li>
 *   <li>{@link io.github.fludakit.tx.TransactionSynchronization} — lifecycle callbacks</li>
 *   <li>{@link io.github.fludakit.tx.SavepointManager} — savepoints, which make {@code NESTED}
 *       work</li>
 * </ul>
 *
 * <h2>Wiring a transaction manager</h2>
 * <p>Both built-in managers are plain objects; the CDI module adds discovery, not a different
 * lifecycle. The steps are the same in either case: provide the resource, create the manager over
 * it, then use the manager directly or let the interceptor drive it.</p>
 *
 * <p><strong>JDBC</strong></p>
 * <ol>
 *   <li>Create or obtain the connection pool (a {@code DataSource}). The manager opens connections
 *       from it.</li>
 *   <li>Create {@code JdbcTransactionManager} over that pool:
 *       {@code new JdbcTransactionManager(pool)}. Pass the pool itself, never a
 *       {@code TransactionAwareDataSourceProxy}.</li>
 *   <li>Expose {@code manager.getTransactionAwareDataSource()} to application JDBC code, so its
 *       {@code getConnection()} calls join the running transaction.</li>
 *   <li>Use the manager programmatically through {@code execute}, or add {@code fluda-tx-cdi} and
 *       annotate methods with {@code @Transactional}. Under CDI,
 *       {@code JdbcTransactionManagerProducer} performs steps 1 and 2 and honours a
 *       {@code @RawDataSource} qualified pool.</li>
 * </ol>
 *
 * <p><strong>JPA</strong></p>
 * <ol>
 *   <li>Create or obtain the pool, as above.</li>
 *   <li>Build the {@code EntityManagerFactory} over that pool. Under CDI the
 *       {@code PersistenceConfigurationProducer} and {@code EntityManagerFactoryProducer} do this,
 *       applying any {@code PersistenceConfigurationCustomizer} beans; in plain Java use
 *       {@code Persistence.createEntityManagerFactory(unitName, properties)}.</li>
 *   <li>Create {@code JpaTransactionManager} over the factory:
 *       {@code new JpaTransactionManager(emf)}. The manager borrows the factory and never closes
 *       it, so the factory's owner stays responsible for its shutdown.</li>
 *   <li>Use the manager, or {@code @Transactional}. Under CDI,
 *       {@code JpaTransactionManagerProducer} produces it over the container's factory and picks
 *       up a {@code ConnectionExtractor} bean when the application declares one.</li>
 * </ol>
 *
 * <h2>Programmatic usage</h2>
 * <pre>{@code
 * @Inject
 * TransactionalOperations<EntityManager> txOps;
 *
 * public Long createUser(User user) throws Exception {
 *     return txOps.execute((em, status) -> {
 *         em.persist(user);
 *         return user.getId();
 *     });
 * }
 * }</pre>
 *
 * <h2>Declarative usage</h2>
 * <p>With the {@code fluda-tx-cdi} module on the classpath, the standard Jakarta annotation
 * works unchanged. The interceptor uses the definition precomputed at deployment — resolving and
 * caching it on first use for a class the extension never saw — and drives the
 * same {@code execute} path, so the two front-ends share one lifecycle and one rollback rule
 * set.</p>
 * <pre>{@code
 * @ApplicationScoped
 * public class UserService {
 *     @Inject EntityManager em;
 *
 *     @Transactional
 *     public void createUser(User user) {
 *         em.persist(user);
 *     }
 * }
 * }</pre>
 *
 * <h2>Propagation</h2>
 * <table border="1">
 *   <caption>Propagation behaviour</caption>
 *   <tr><th>{@code TxType}</th><th>No ambient transaction</th><th>Ambient transaction</th></tr>
 *   <tr><td>{@code REQUIRED}</td><td>begin</td><td>join</td></tr>
 *   <tr><td>{@code REQUIRES_NEW}</td><td>begin</td><td>suspend, begin, resume</td></tr>
 *   <tr><td>{@code SUPPORTS}</td><td>no transaction</td><td>join</td></tr>
 *   <tr><td>{@code NOT_SUPPORTED}</td><td>no transaction</td><td>suspend</td></tr>
 *   <tr><td>{@code MANDATORY}</td><td>fail</td><td>join</td></tr>
 *   <tr><td>{@code NEVER}</td><td>no transaction</td><td>fail</td></tr>
 *   <tr><td>{@code NESTED}</td><td>begin</td><td>savepoint section</td></tr>
 * </table>
 *
 * <h2>Built-in implementations</h2>
 * <ul>
 *   <li>{@code io.github.fludakit.tx.jdbc.JdbcTransactionManager} — {@code Connection}</li>
 *   <li>{@code io.github.fludakit.tx.jpa.JpaTransactionManager} — {@code EntityManager}</li>
 * </ul>
 *
 * <h2>Exceptions</h2>
 * <p>Every failure derives from {@link io.github.fludakit.tx.TransactionException}, which
 * extends the spec's {@code jakarta.transaction.TransactionalException}. Callers can therefore
 * catch either type on either path.</p>
 *
 * @see io.github.fludakit.tx.TransactionManager
 * @see io.github.fludakit.tx.TransactionDefinition
 * @see io.github.fludakit.tx.TransactionStatus
 * @see io.github.fludakit.tx.TransactionalOperations
 * @see io.github.fludakit.tx.support.AbstractTransactionOperations
 */
package io.github.fludakit.tx;
