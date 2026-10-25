package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * CDI producer for transaction-scoped {@link EntityManager}.
 *
 * <p>This producer enables direct injection of {@code EntityManager} in CDI beans,
 * eliminating the need to call {@link JpaHelper#currentEntityManager()} statically.</p>
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
 *     public TransactionManager transactionManager(DataSource ds) {
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
 * <p>The producer returns a dynamic proxy that defers EntityManager lookup until a method is
 * invoked. This allows injection at bean creation time (before any transaction is active); each
 * invocation then resolves the transaction-bound EntityManager, so the proxy stays correct across
 * sequential transactions.</p>
 *
 * @see JpaHelper#currentEntityManager()
 */
@Dependent
public class EntityManagerProducer {

    /**
     * Produces a lazy-resolving {@link EntityManager} proxy.
     *
     * <p>The returned proxy defers EntityManager lookup until each method invocation.
     * When a method is called, it retrieves the EntityManager from the active
     * {@link TransactionContext} via {@link JpaHelper#currentEntityManager()}.
     * If no transaction is active, throws {@link IllegalStateException}.</p>
     *
     * @return a proxy that resolves to the transaction-scoped EntityManager on each use
     * @throws IllegalStateException if no transaction is active when a method is invoked
     */
    @Produces
    public EntityManager entityManager() {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] { EntityManager.class },
                new LazyEntityManagerHandler()
        );
    }

    /**
     * InvocationHandler that resolves the transaction-bound EntityManager on every method call.
     *
     * <p>Resolution cannot be cached: the produced bean is {@code @Dependent}, so an
     * {@code @ApplicationScoped} client keeps this handler across transactions, and the
     * EntityManager bound to the first transaction is closed by that transaction's
     * {@code afterCompletion} — a cached instance would hand the second transaction a closed
     * EntityManager. The lookup is by type, so no factory instance is needed and it costs
     * nothing but a map read.</p>
     */
    private static class LazyEntityManagerHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            EntityManager em = JpaHelper.currentEntityManager();
            try {
                return method.invoke(em, args);
            } catch (InvocationTargetException e) {
                // surface the target's exception itself; for a checked undeclared cause the proxy
                // machinery would otherwise wrap it in UndeclaredThrowableException
                throw e.getCause();
            }
        }
    }
}
