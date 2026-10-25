package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

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
 * <p>The lookup is by type ({@code EntityManager.class}), so it works identically with raw
 * instances and CDI client proxies.</p>
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
        TransactionContext ctx = TransactionContextHolder.get();
        if (ctx == null || !ctx.isActualTransactionActive()) {
            throw new IllegalStateException("No transaction is active");
        }
        EntityManager em = ctx.getResource(EntityManager.class);
        if (em == null) {
            throw new IllegalStateException("No EntityManager bound to the current transaction");
        }
        return em;
    }
}
