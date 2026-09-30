package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * CDI producer for transaction-scoped {@link EntityManager}.
 *
 * <p>This producer enables direct injection of {@code EntityManager} in CDI beans,
 * eliminating the need to call {@link JpaTransactionManager#currentEntityManager()} statically.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class UserService {
 *
 *     @Inject
 *     private EntityManager em;  // Transaction-scoped
 *
 *     @Transactional
 *     public void createUser(User user) {
 *         em.persist(user);  // Works within @Transactional boundary
 *     }
 * }
 * }</pre>
 *
 * <h2>Requirements</h2>
 * <p>The application must provide an {@link EntityManagerFactory} bean. Typically this is
 * an {@code @ApplicationScoped} producer:</p>
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class JpaConfig {
 *
 *     private final EntityManagerFactory emf;
 *
 *     public JpaConfig() {
 *         this.emf = Persistence.createEntityManagerFactory("myPU");
 *     }
 *
 *     @Produces
 *     @ApplicationScoped
 *     public EntityManagerFactory entityManagerFactory() {
 *         return emf;
 *     }
 *
 *     @Produces
 *     @ApplicationScoped
 *     public PlatformTransactionManager transactionManager(DataSource ds) {
 *         return new JpaTransactionManager(emf, ds);
 *     }
 * }
 * }</pre>
 *
 * <h2>Scope</h2>
 * <p>The produced {@code EntityManager} uses {@link Dependent} scope. Each injection point
 * receives a CDI proxy that delegates to the transaction-bound EntityManager from
 * {@link TransactionContext}. Within a single transaction, all injection points resolve
 * to the same underlying EntityManager instance.</p>
 *
 * <h2>Lazy Resolution</h2>
 * <p>The producer returns a dynamic proxy that defers EntityManager lookup until first use.
 * This allows injection at bean creation time (before any transaction is active) while still
 * providing the transaction-bound EntityManager when methods are invoked within a transaction.</p>
 *
 * <h2>CDI Proxy Handling</h2>
 * <p>The injected {@code EntityManagerFactory} may be a CDI client proxy (for {@code @ApplicationScoped}
 * beans). The {@link JpaTransactionManager#currentEntityManager(EntityManagerFactory)} method
 * handles this by falling back to type-based resource lookup when identity-based lookup fails.</p>
 *
 * @see JpaTransactionManager#currentEntityManager()
 * @see JpaTransactionManager#currentEntityManager(EntityManagerFactory)
 */
@Dependent
public class EntityManagerProducer {

    @Inject
    private EntityManagerFactory entityManagerFactory;

    /**
     * Produces a lazy-resolving {@link EntityManager} proxy.
     *
     * <p>The returned proxy defers EntityManager lookup until first method invocation.
     * When a method is called, it retrieves the EntityManager from the active
     * {@link TransactionContext} via {@link JpaTransactionManager#currentEntityManager(EntityManagerFactory)}.
     * If no transaction is active, throws {@link IllegalStateException}.</p>
     *
     * @return a proxy that resolves to the transaction-scoped EntityManager on first use
     * @throws IllegalStateException if no transaction is active when a method is invoked
     */
    @Produces
    public EntityManager entityManager() {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] { EntityManager.class },
                new LazyEntityManagerHandler(entityManagerFactory)
        );
    }

    /**
     * InvocationHandler that lazily resolves the EntityManager on first method call.
     */
    private static class LazyEntityManagerHandler implements InvocationHandler {

        private final EntityManagerFactory emf;
        private EntityManager resolved;

        LazyEntityManagerHandler(EntityManagerFactory emf) {
            this.emf = emf;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (resolved == null) {
                resolved = JpaTransactionManager.currentEntityManager(emf);
            }
            return method.invoke(resolved, args);
        }
    }
}
