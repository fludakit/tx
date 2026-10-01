package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Utility methods for working with JPA in the context of FluDa transactions.
 *
 * <p>Provides static methods to retrieve the current {@link EntityManager} bound to the
 * active transaction. These methods handle CDI proxy identity issues that arise when
 * EntityManagerFactory is injected via CDI.</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Single EntityManagerFactory case
 * EntityManager em = JpaHelper.currentEntityManager();
 * em.persist(entity);
 *
 * // Multiple EntityManagerFactories case
 * EntityManager em = JpaHelper.currentEntityManager(entityManagerFactory);
 * em.merge(entity);
 * }</pre>
 *
 * <h2>CDI Proxy Handling</h2>
 * <p>When using CDI injection, the injected {@link EntityManagerFactory} is a client proxy
 * that differs in identity from the raw factory instance used as the resource key in
 * {@link TransactionContext}. The {@link #currentEntityManager(EntityManagerFactory)} method
 * handles this by falling back to a type-based search when the direct lookup fails.</p>
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
     * <p>For the common single-EMF case. If multiple EntityManagerFactories are in use,
     * prefer {@link #currentEntityManager(EntityManagerFactory)} with the raw (non-proxy)
     * factory instance.</p>
     *
     * @return the current EntityManager
     * @throws IllegalStateException if no transaction is active or no EntityManager is bound
     */
    public static EntityManager currentEntityManager() {
        TransactionContext ctx = TransactionContextHolder.get();
        if (ctx == null || !ctx.isActualTransactionActive()) {
            throw new IllegalStateException("No transaction is active");
        }
        EntityManager em = ctx.findResourceByType(EntityManager.class);
        if (em == null) {
            throw new IllegalStateException("No EntityManager bound to the current transaction");
        }
        return em;
    }

    /**
     * Returns the {@link EntityManager} bound to the current transaction for the given factory.
     *
     * <p>The {@code emf} parameter should be the raw {@link EntityManagerFactory} instance,
     * not a CDI proxy. When using CDI injection, prefer {@link #currentEntityManager()} instead,
     * since injected EMF references are client proxies that differ in identity from the raw
     * factory used as the resource key.</p>
     *
     * @param emf the EntityManagerFactory
     * @return the current EntityManager for the given factory
     * @throws IllegalStateException if no transaction is active or no EntityManager is bound
     */
    public static EntityManager currentEntityManager(EntityManagerFactory emf) {
        TransactionContext ctx = TransactionContextHolder.get();
        if (ctx == null || !ctx.isActualTransactionActive()) {
            throw new IllegalStateException("No transaction is active");
        }
        EntityManager em = ctx.getResource(emf, EntityManager.class);
        if (em != null) {
            return em;
        }
        // Fallback for CDI proxy scenario: search by type
        em = ctx.findResourceByType(EntityManager.class);
        if (em != null) {
            return em;
        }
        throw new IllegalStateException("No EntityManager bound for this EntityManagerFactory");
    }
}
