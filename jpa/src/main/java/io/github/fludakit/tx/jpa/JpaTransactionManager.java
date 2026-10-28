package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.JdbcSavepoint;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import io.github.fludakit.tx.support.TransactionSavepointManager;
import io.github.fludakit.tx.support.TransactionStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;

/**
 * JPA-based {@link TransactionManager} that manages transactions through a standard
 * {@link EntityManagerFactory}.
 *
 * <p>Provider-agnostic: uses only {@code jakarta.persistence} API for EntityManager lifecycle.
 * In {@link #getTransaction}, it creates an {@link EntityManager}, begins a resource-local
 * {@link EntityTransaction}, and extracts the underlying JDBC {@link Connection} via a
 * configurable {@link ConnectionExtractor}. Both the EntityManager and the Connection are
 * bound to the {@link TransactionContext}:</p>
 * <ul>
 *   <li>EntityManager is bound under {@code EntityManager.class} — retrieve via
 *       {@link #getResource(TransactionContext)} or {@link JpaHelper#currentEntityManager()}</li>
 *   <li>Connection is bound under {@code Connection.class} — enabling JDBC fallback through
 *       {@code TransactionAwareDataSourceProxy}</li>
 * </ul>
 *
 * <p>This dual binding allows mixing JPA operations and plain JDBC SQL within the same
 * transaction, both operating on the same physical connection.</p>
 */
public class JpaTransactionManager implements TransactionManager<EntityManager> {

    private static final Logger LOG = Logger.getLogger(JpaTransactionManager.class.getName());

    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;
    private final ConnectionExtractor connectionExtractor;

    public JpaTransactionManager(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        this(entityManagerFactory, dataSource, ConnectionExtractor.DEFAULT);
    }

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

    /**
     * The {@link EntityManager} bound to the transaction on the current thread. Outside an
     * active transaction there is no bound EntityManager — returns {@code null}.
     */
    @Override
    public EntityManager getResource() {
        TransactionContext ctx = TransactionContextHolder.get();
        return ctx != null ? ctx.getResource(EntityManager.class) : null;
    }

    private static final String SAVEPOINT_PREFIX = "fluda_sp_";

    private final AtomicInteger savepointCounter = new AtomicInteger();

    /**
     * JDBC savepoints over the extracted connection — inherited from whatever the driver offers.
     * Providers that intercept SQL at a level savepoints bypass may not observe the partial
     * rollback in their persistence context; flush the persistence context before a nested
     * section that relies on it.
     */
    private final class ConnectionSavepointManager implements TransactionSavepointManager {

        private final Connection connection;

        ConnectionSavepointManager(Connection connection) {
            this.connection = connection;
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            try {
                String name = SAVEPOINT_PREFIX + savepointCounter.incrementAndGet();
                return new JdbcSavepoint(connection.setSavepoint(name), name);
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not create JDBC savepoint", ex);
            }
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            try {
                connection.rollback(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not roll back to JDBC savepoint", ex);
            }
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            try {
                connection.releaseSavepoint(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not release JDBC savepoint", ex);
            }
        }
    }

    @Override
    public TransactionContext getTransaction(TransactionDefinition definition) throws TransactionException {
        EntityManager em;
        try {
            em = entityManagerFactory.createEntityManager();
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not open JPA EntityManager for transaction", ex);
        }
        try {
            em.getTransaction().begin();

            Connection connection = connectionExtractor.extract(em);

            TransactionContext context = new TransactionContext(true, TransactionStatus.initial(
                    definition, new ConnectionSavepointManager(connection)));
            context.bindResource(EntityManager.class, em);
            context.bindResource(Connection.class, connection);
            context.registerCallback(new EntityManagerCleanup(em));
            return context;
        } catch (RuntimeException ex) {
            // begin() may have started a transaction and the extractor may throw before anything
            // is bound to clean up — roll back and close so nothing leaks, then translate
            closeAfterFailedBegin(em, ex);
            throw new TransactionSystemException("Could not begin JPA transaction", ex);
        }
    }

    @Override
    public void commit(TransactionContext context) throws TransactionException {
        EntityManager em = boundEntityManager(context);
        try {
            em.flush();
            em.getTransaction().commit();
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not commit JPA transaction", ex);
        }
    }

    @Override
    public void rollback(TransactionContext context) throws TransactionException {
        EntityManager em = boundEntityManager(context);
        try {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
        } catch (PersistenceException ex) {
            throw new TransactionSystemException("Could not rollback JPA transaction", ex);
        }
    }

    private EntityManager boundEntityManager(TransactionContext context) {
        EntityManager em = context.getResource(EntityManager.class);
        if (em == null) {
            throw new IllegalStateException("No EntityManager bound to the active transaction");
        }
        return em;
    }

    /**
     * Rolls back and closes an EntityManager whose transaction failed to start, preserving any
     * cleanup failure as a suppressed exception on the original cause.
     */
    private static void closeAfterFailedBegin(EntityManager em, RuntimeException cause) {
        try {
            if (em.isOpen() && em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
        } catch (RuntimeException rollbackEx) {
            cause.addSuppressed(rollbackEx);
        }
        try {
            if (em.isOpen()) {
                em.close();
            }
        } catch (RuntimeException closeEx) {
            cause.addSuppressed(closeEx);
        }
    }

    private static final class EntityManagerCleanup implements TransactionCallback {

        private final EntityManager entityManager;

        EntityManagerCleanup(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        @Override
        public void afterCompletion(CompletionStatus status) {
            try {
                if (entityManager.isOpen()) {
                    entityManager.close();
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Failed to close JPA EntityManager", ex);
            }
        }
    }
}
