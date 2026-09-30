package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import io.github.fludakit.tx.support.TransactionCallback;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.transaction.TransactionScoped;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

/**
 * CDI {@link jakarta.enterprise.context.spi.Context} implementation for
 * {@link TransactionScoped @TransactionScoped} beans.
 *
 * <p>This context stores bean instances in the active {@link TransactionContext},
 * tying their lifecycle to the transaction. When the transaction completes,
 * all transaction-scoped beans are destroyed.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @TransactionScoped
 * public class TransactionalCache {
 *     private final Map<Object, Object> cache = new HashMap<>();
 *
 *     public void put(Object key, Object value) {
 *         cache.put(key, value);
 *     }
 *
 *     public Object get(Object key) {
 *         return cache.get(key);
 *     }
 * }
 *
 * @ApplicationScoped
 * public class UserService {
 *     @Inject TransactionalCache cache;  // One instance per transaction
 *
 *     @Transactional
 *     public User findUser(Long id) {
 *         User user = (User) cache.get(id);
 *         if (user == null) {
 *             user = loadFromDatabase(id);
 *             cache.put(id, user);
 *         }
 *         return user;
 *     }
 * }
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Beans are created on first access within a transaction</li>
 *   <li>The same instance is returned for subsequent accesses within the same transaction</li>
 *   <li>All instances are destroyed when the transaction completes (commit or rollback)</li>
 * </ul>
 *
 * <h2>Integration</h2>
 * <p>This context is registered by {@link TransactionalCdiExtension} and activated by
 * {@link TransactionalInterceptor} when a transaction begins.</p>
 *
 * @see TransactionScoped
 * @see TransactionalCdiExtension
 */
public class TransactionScopeContext implements jakarta.enterprise.context.spi.Context {

    private static final String BEANS_KEY = TransactionScopeContext.class.getName() + ".beans";

    @Override
    public Class<? extends Annotation> getScope() {
        return TransactionScoped.class;
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        BeanStore store = getBeanStore();
        
        @SuppressWarnings("unchecked")
        T instance = (T) store.get(contextual);
        if (instance == null && creationalContext != null) {
            instance = contextual.create(creationalContext);
            store.put(contextual, instance, creationalContext);
        }
        return instance;
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        BeanStore store = getBeanStore();
        return store.get(contextual);
    }

    @Override
    public boolean isActive() {
        TransactionContext ctx = TransactionContextHolder.get();
        return ctx != null && ctx.isActualTransactionActive();
    }

    private BeanStore getBeanStore() {
        TransactionContext ctx = TransactionContextHolder.get();
        if (ctx == null || !ctx.isActualTransactionActive()) {
            throw new IllegalStateException("No active transaction for @TransactionScoped bean");
        }

        BeanStore store = (BeanStore) ctx.getResource(BEANS_KEY);
        if (store == null) {
            store = new BeanStore();
            ctx.bindResource(BEANS_KEY, store);
            ctx.registerCallback(store);
        }
        return store;
    }

    /**
     * Stores transaction-scoped bean instances and handles cleanup.
     */
    private static class BeanStore implements TransactionCallback {

        private final Map<Contextual<?>, InstanceInfo<?>> instances = new HashMap<>();

        @SuppressWarnings("unchecked")
        <T> T get(Contextual<T> contextual) {
            InstanceInfo<?> info = instances.get(contextual);
            return info != null ? (T) info.instance : null;
        }

        <T> void put(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {
            instances.put(contextual, new InstanceInfo<>(instance, contextual, creationalContext));
        }

        @Override
        public void afterCompletion(CompletionStatus status) {
            for (InstanceInfo<?> info : instances.values()) {
                destroyInstance(info);
            }
            instances.clear();
        }

        @SuppressWarnings("unchecked")
        private <T> void destroyInstance(InstanceInfo<T> info) {
            try {
                info.contextual.destroy(info.instance, info.creationalContext);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(TransactionScopeContext.class.getName())
                        .warning("Failed to destroy @TransactionScoped bean: " + e.getMessage());
            }
        }

        private static class InstanceInfo<T> {
            final T instance;
            final Contextual<T> contextual;
            final CreationalContext<T> creationalContext;

            InstanceInfo(T instance, Contextual<T> contextual, CreationalContext<T> creationalContext) {
                this.instance = instance;
                this.contextual = contextual;
                this.creationalContext = creationalContext;
            }
        }
    }
}
