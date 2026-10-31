package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import jakarta.persistence.EntityManager;

/**
 * Utility methods for working with JPA in the context of FluDa transactions.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EntityManager em = JpaHelper.currentEntityManager();
 * em.persist(entity);
 * }</pre>
 *
 * <p>The EntityManager is the resource of a JPA transaction, so the lookup is by type and works
 * identically with raw instances and CDI client proxies.</p>
 *
 * @see JpaTransactionManager
 */
public final class JpaHelper {

    private JpaHelper() {
        throw new AssertionError("No instances");
    }

    /**
     * Returns the {@link EntityManager} bound to the current transaction.
     *
     * @return the current EntityManager
     * @throws IllegalStateException if no transaction is active or no EntityManager is bound
     */
    public static EntityManager currentEntityManager() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        if (status == null || !status.isActualTransactionActive() || status.isCompleted()) {
            throw new IllegalStateException("No transaction is active");
        }
        EntityManager entityManager = resolve(status);
        if (entityManager == null) {
            throw new IllegalStateException("No EntityManager is bound to the current transaction");
        }
        return entityManager;
    }

    /**
     * The EntityManager of a transaction: the status resource when the transaction is
     * JPA-managed, the {@code EntityManager.class} attribute when something else manages it.
     */
    static EntityManager resolve(TransactionStatus<?> status) {
        if (status.getResource() instanceof EntityManager entityManager) {
            return entityManager;
        }
        return status.getAttribute(EntityManager.class);
    }
}
