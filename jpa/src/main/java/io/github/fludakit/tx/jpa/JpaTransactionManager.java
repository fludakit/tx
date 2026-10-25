package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

import java.sql.Connection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;

/**
 * JPA-based {@link PlatformTransactionManager} that manages transactions through a standard
 * {@link EntityManagerFactory}.
 *
 * <p>Provider-agnostic: uses only {@code jakarta.persistence} API for EntityManager lifecycle.
 * In {@link #getTransaction}, it creates an {@link EntityManager}, begins a resource-local
 * {@link EntityTransaction}, and extracts the underlying JDBC {@link Connection} via a
 * configurable {@link ConnectionExtractor}. Both the EntityManager and the Connection are
 * bound to the {@link TransactionContext}:</p>
 * <ul>
 *   <li>EntityManager is bound under the {@code EntityManagerFactory} key — retrieve via
 *       {@link #currentEntityManager(EntityManagerFactory)}</li>
 *   <li>Connection is bound under the {@code DataSource} key — enabling JDBC fallback through
 *       {@code TransactionAwareDataSourceProxy}</li>
 * </ul>
 *
 * <p>This dual binding allows mixing JPA operations and plain JDBC SQL within the same
 * {@code @Transactional} method, both operating on the same physical connection.</p>
 */
public class JpaTransactionManager implements PlatformTransactionManager {

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

            TransactionContext context = new TransactionContext(true);
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
