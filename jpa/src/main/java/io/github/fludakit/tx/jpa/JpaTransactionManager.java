package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;
import io.github.fludakit.tx.support.JdbcSavepointManager;

import java.sql.Connection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;

/**
 * Resource-local transaction manager for JPA, over a standard {@link EntityManagerFactory}.
 *
 * <p>Provider-agnostic: only {@code jakarta.persistence} is used. {@link #doBegin} creates an
 * {@link EntityManager}, begins its resource-local transaction, extracts the underlying JDBC
 * {@link Connection}, and binds both to the status:</p>
 * <ul>
 *   <li>the {@link EntityManager} is the status <em>resource</em> — this is
 *       {@code TransactionManager<EntityManager>}, and {@link JpaHelper#currentEntityManager()}
 *       resolves it</li>
 *   <li>the {@link Connection} is bound as a {@code Connection.class} attribute, so plain JDBC
 *       code in the same transaction shares the physical connection and
 *       {@code TransactionAwareDataSourceProxy} can hand it out</li>
 * </ul>
 *
 * <h2>Where the EntityManagerFactory comes from</h2>
 * <p>The manager only borrows the factory and never creates, owns, or closes it. In CDI the
 * container hands it one: {@link PersistenceConfigurationProducer} builds a
 * {@link jakarta.persistence.PersistenceConfiguration} over the application's pool and hands it to
 * {@link EntityManagerFactoryProducer}, which creates the factory once and closes it at shutdown.
 * {@link JpaTransactionManagerProducer} then produces this manager over that factory, using a
 * {@link ConnectionExtractor} bean when the application declares one.</p>
 *
 * <h2>Creating a manager</h2>
 * <p>In plain Java, build the factory yourself and pass it in; the factory stays the caller's, so
 * close it when the application shuts down. The two-argument constructor accepts a different
 * {@link ConnectionExtractor} for a provider that needs its own connection lookup.</p>
 *
 * <pre>{@code
 * EntityManagerFactory emf = JpaBootstrap.create("myPU", pool);
 * JpaTransactionManager txManager = new JpaTransactionManager(emf);
 *
 * // or, with a provider-specific extraction strategy
 * JpaTransactionManager custom = new JpaTransactionManager(emf, extractor);
 * }</pre>
 *
 * <p>Under CDI there is nothing to construct: inject the concrete manager, the
 * {@code TransactionManager} interface, or the {@code EntityManagerFactory} itself. The container
 * builds the factory over the same pool the JDBC code uses and closes it when the container
 * shuts down:</p>
 *
 * <pre>{@code
 * @Inject
 * JpaTransactionManager txManager;
 * }</pre>
 *
 * <h2>Pass the raw pool, not the transaction-aware proxy</h2>
 * <p>The {@link DataSource} the factory is built on is the connection pool itself, never a
 * {@code io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy}. This manager does not open
 * connections — the persistence provider does — so there is nothing for that proxy to route. The
 * sharing runs the other way: after {@code EntityTransaction.begin()}, {@link #doBegin} extracts
 * the provider's JDBC {@link Connection} and binds it as a {@code Connection.class} attribute,
 * which is what the proxy resolves for application JDBC code.</p>
 *
 * <p>A provider that obtained its connection <em>through</em> the proxy would receive the proxy's
 * close-suppressing view, so its {@code close()} would return nothing to the pool, and the proxy's
 * {@code beforeCommit} would commit the very connection this manager then commits again. Expose
 * the proxy to application code instead, over the same pool the persistence unit was built on:</p>
 *
 * <pre>{@code
 * // persistence provider side: the raw pool
 * EntityManagerFactory emf = JpaBootstrap.create("myPU", pool);
 * JpaTransactionManager txManager = new JpaTransactionManager(emf);
 *
 * // application side: a view of the same pool whose connections join the transaction
 * DataSource appDataSource = new TransactionAwareDataSourceProxy(pool);
 * }</pre>
 *
 * <p>Mixed JDBC/JPA work shares one transaction because the manager binds the provider's
 * connection; only the arrangement above leaves the provider in charge of that connection's
 * lifecycle. Plain JDBC on it still runs behind the persistence context, so flush the
 * {@link EntityManager} before the JDBC work when ordering matters.</p>
 *
 * <h2>Savepoints</h2>
 * <p>{@code NESTED} sections use JDBC savepoints over the extracted connection. Flush the
 * persistence context before relying on a partial rollback: a provider that keeps changes in its
 * own first-level cache does not observe a JDBC rollback to a savepoint on its own.</p>
 */
public class JpaTransactionManager extends AbstractTransactionOperations<EntityManager> {

    private static final Logger LOG = Logger.getLogger(JpaTransactionManager.class.getName());

    private final EntityManagerFactory entityManagerFactory;
    private final ConnectionExtractor connectionExtractor;

    /**
     * Creates a manager over the given factory, extracting the connection with
     * {@link ConnectionExtractor#DEFAULT}.
     *
     * <p>The factory belongs to the caller: this manager never closes it.</p>
     *
     * @param entityManagerFactory the JPA entity manager factory
     */
    public JpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, ConnectionExtractor.DEFAULT);
    }

    /**
     * Creates a manager over the given factory and extraction strategy.
     *
     * <p>The factory belongs to the caller: this manager never closes it.</p>
     *
     * @param entityManagerFactory the JPA entity manager factory, owned by the caller
     * @param connectionExtractor  the strategy that finds the underlying connection
     */
    public JpaTransactionManager(EntityManagerFactory entityManagerFactory,
                                 ConnectionExtractor connectionExtractor) {
        this.entityManagerFactory =
                Objects.requireNonNull(entityManagerFactory, "entityManagerFactory must not be null");
        this.connectionExtractor = Objects.requireNonNull(connectionExtractor, "connectionExtractor must not be null");
    }

    /**
     * The factory this manager creates {@link EntityManager}s from.
     *
     * @return the factory supplied at construction
     */
    public EntityManagerFactory getEntityManagerFactory() {
        return entityManagerFactory;
    }

    @Override
    protected void doBegin(DefaultTransactionStatus<EntityManager> status) {
        EntityManager entityManager;
        try {
            entityManager = entityManagerFactory.createEntityManager();
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not create an EntityManager for the transaction", ex);
        }

        try {
            entityManager.getTransaction().begin();
            Connection connection = connectionExtractor.extract(entityManager);
            if (connection != null) {
                // bound as an attribute, not as the resource: the resource of a JPA transaction is
                // the EntityManager, and the connection is what JDBC interop looks up
                status.putAttributeIfAbsent(Connection.class, connection);
            }
        } catch (RuntimeException ex) {
            // begin() may have started a transaction and extraction may fail before anything is
            // bound to clean it up - release it here
            closeAfterFailedBegin(entityManager, ex);
            throw new TransactionSystemException("Could not begin the JPA transaction", ex);
        }

        bindResource(status, entityManager);
        status.registerSynchronization(new EntityManagerCleanup(entityManager));
    }

    @Override
    protected void doCommit(DefaultTransactionStatus<EntityManager> status) {
        EntityManager entityManager = managed(status);
        try {
            entityManager.flush();
            entityManager.getTransaction().commit();
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not commit the JPA transaction", ex);
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus<EntityManager> status) {
        EntityManager entityManager = managed(status);
        try {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not roll back the JPA transaction", ex);
        }
    }

    @Override
    protected void doNestedRollback(DefaultTransactionStatus<EntityManager> status) {
        EntityManager entityManager = managed(status);
        try {
            // a JDBC savepoint only reaches work the persistence context has already flushed
            entityManager.flush();
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not flush the JPA transaction before a savepoint rollback", ex);
        }
        super.doNestedRollback(status);
    }

    @Override
    protected SavepointManager newSavepointManager(DefaultTransactionStatus<EntityManager> status) {
        return new JdbcSavepointManager(() -> status.getAttribute(Connection.class));
    }

    private EntityManager managed(DefaultTransactionStatus<EntityManager> status) {
        EntityManager entityManager = status.getResource();
        if (entityManager == null) {
            throw new IllegalStateException("No EntityManager is bound to the transaction " + status);
        }
        return entityManager;
    }

    /**
     * Rolls back and closes an EntityManager whose transaction failed to start, preserving any
     * cleanup failure as a suppressed exception on the original cause.
     */
    private void closeAfterFailedBegin(EntityManager entityManager, RuntimeException cause) {
        try {
            if (entityManager.isOpen() && entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
        try {
            if (entityManager.isOpen()) {
                entityManager.close();
            }
        } catch (RuntimeException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    /**
     * Closes the EntityManager when the transaction completes, whatever the outcome.
     */
    private static final class EntityManagerCleanup implements TransactionSynchronization {

        private final EntityManager entityManager;

        EntityManagerCleanup(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        @Override
        public void afterCompletion(Status status) {
            try {
                if (entityManager.isOpen()) {
                    entityManager.close();
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Failed to close the JPA EntityManager", ex);
            }
        }
    }
}
