package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;
import io.github.fludakit.tx.support.JdbcSavepointManager;

import java.sql.Connection;
import java.util.Map;
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
 * <p>The manager is built either around an existing factory, or — the preferred form when the
 * application already owns a pool — from a persistence unit name plus that {@link DataSource}:
 * {@code new JpaTransactionManager("myPU", dataSource)} creates the factory through
 * {@link JpaBootstrap} on the same pool the application's JDBC code uses, so callers never pass
 * the factory and the pool twice. A factory created this way is owned by the manager and closed by
 * {@link #close()}; one passed in by the caller is not.</p>
 *
 * <h2>Savepoints</h2>
 * <p>{@code NESTED} sections use JDBC savepoints over the extracted connection. Flush the
 * persistence context before relying on a partial rollback: a provider that keeps changes in its
 * own first-level cache does not observe a JDBC rollback to a savepoint on its own.</p>
 */
public class JpaTransactionManager extends AbstractTransactionOperations<EntityManager> implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(JpaTransactionManager.class.getName());

    private final EntityManagerFactory entityManagerFactory;
    private final ConnectionExtractor connectionExtractor;
    private final boolean ownsEntityManagerFactory;

    /**
     * Creates a manager over an existing factory, extracting the connection with
     * {@link ConnectionExtractor#DEFAULT}.
     *
     * @param entityManagerFactory the JPA entity manager factory
     */
    public JpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, ConnectionExtractor.DEFAULT, false);
    }

    /**
     * @param entityManagerFactory the JPA entity manager factory
     * @param connectionExtractor  the strategy that finds the underlying connection
     */
    public JpaTransactionManager(EntityManagerFactory entityManagerFactory,
                                 ConnectionExtractor connectionExtractor) {
        this(entityManagerFactory, connectionExtractor, false);
    }

    /**
     * Creates the {@link EntityManagerFactory} for the persistence unit over the given pool.
     *
     * <p>The factory is created through {@link JpaBootstrap#create(String, DataSource)}, so the
     * persistence unit's {@code nonJtaDataSource} is this exact {@link DataSource} instance — the
     * same one the application's JDBC code uses.</p>
     *
     * @param persistenceUnitName the persistence unit name ({@code persistence.xml})
     * @param dataSource          the application's DataSource, shared with JDBC code
     */
    public JpaTransactionManager(String persistenceUnitName, DataSource dataSource) {
        this(persistenceUnitName, dataSource, Map.of());
    }

    /**
     * As {@link #JpaTransactionManager(String, DataSource)} with additional provider properties
     * (dialect, schema generation, vendor keys).
     *
     * @param persistenceUnitName the persistence unit name ({@code persistence.xml})
     * @param dataSource          the application's DataSource, shared with JDBC code
     * @param properties          additional provider properties, added after the DataSource binding
     */
    public JpaTransactionManager(String persistenceUnitName, DataSource dataSource,
                                 Map<String, Object> properties) {
        this(JpaBootstrap.create(
                        Objects.requireNonNull(persistenceUnitName, "persistenceUnitName must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"),
                        Objects.requireNonNull(properties, "properties must not be null")),
                ConnectionExtractor.DEFAULT,
                true);
    }

    private JpaTransactionManager(EntityManagerFactory entityManagerFactory,
                                  ConnectionExtractor connectionExtractor,
                                  boolean ownsEntityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory must not be null");
        this.connectionExtractor = Objects.requireNonNull(connectionExtractor, "connectionExtractor must not be null");
        this.ownsEntityManagerFactory = ownsEntityManagerFactory;
    }

    public EntityManagerFactory getEntityManagerFactory() {
        return entityManagerFactory;
    }

    /**
     * Closes the {@link EntityManagerFactory} when this manager created it; a factory supplied by
     * the caller belongs to the caller and is left open.
     */
    @Override
    public void close() {
        if (ownsEntityManagerFactory) {
            try {
                entityManagerFactory.close();
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Failed to close the EntityManagerFactory", ex);
            }
        }
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

    private static EntityManager managed(DefaultTransactionStatus<EntityManager> status) {
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
    private static void closeAfterFailedBegin(EntityManager entityManager, RuntimeException cause) {
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

    /** Closes the EntityManager when the transaction completes, whatever the outcome. */
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
