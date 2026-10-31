package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.JdbcSavepoint;
import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;

import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
 * <h2>Savepoints</h2>
 * <p>{@code NESTED} sections use JDBC savepoints over the extracted connection. Flush the
 * persistence context before relying on a partial rollback: a provider that keeps changes in its
 * own first-level cache does not observe a JDBC rollback to a savepoint on its own.</p>
 */
public class JpaTransactionManager extends AbstractTransactionOperations<EntityManager> {

    private static final Logger LOG = Logger.getLogger(JpaTransactionManager.class.getName());
    private static final String SAVEPOINT_PREFIX = "fluda_sp_";

    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;
    private final ConnectionExtractor connectionExtractor;
    private final AtomicInteger savepointCounter = new AtomicInteger();

    /**
     * Creates a manager that extracts the connection with {@link ConnectionExtractor#DEFAULT}.
     *
     * @param entityManagerFactory the JPA entity manager factory
     * @param dataSource           the pool the manager's JDBC view comes from
     */
    public JpaTransactionManager(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        this(entityManagerFactory, dataSource, ConnectionExtractor.DEFAULT);
    }

    /**
     * @param entityManagerFactory the JPA entity manager factory
     * @param dataSource           the pool the manager's JDBC view comes from
     * @param connectionExtractor  the strategy that finds the underlying connection
     */
    public JpaTransactionManager(EntityManagerFactory entityManagerFactory,
                                 DataSource dataSource,
                                 ConnectionExtractor connectionExtractor) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.connectionExtractor = Objects.requireNonNull(connectionExtractor, "connectionExtractor must not be null");
    }

    public EntityManagerFactory getEntityManagerFactory() {
        return entityManagerFactory;
    }

    public DataSource getDataSource() {
        return dataSource;
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
        return new ConnectionSavepointManager(() -> status.getAttribute(Connection.class));
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

    /** JDBC savepoints over the connection extracted at begin time. */
    private final class ConnectionSavepointManager implements SavepointManager {

        private final Supplier<Connection> connection;

        ConnectionSavepointManager(Supplier<Connection> connection) {
            this.connection = connection;
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            String name = SAVEPOINT_PREFIX + savepointCounter.incrementAndGet();
            Connection connection = connected();
            Savepoint sp;
            try {
                sp = connection.setSavepoint(name);
                return new JdbcSavepoint(sp, name);
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not create a JDBC savepoint", ex);
            }
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            try {
                connected().rollback(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not roll back to the JDBC savepoint", ex);
            }
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            try {
                connected().releaseSavepoint(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not release the JDBC savepoint", ex);
            }
        }

        private Connection connected() {
            Connection bound = connection.get();
            if (bound == null) {
                throw new NestedTransactionNotSupportedException(
                        "No JDBC connection was extracted from the EntityManager - savepoints are unavailable");
            }
            return bound;
        }
    }
}
