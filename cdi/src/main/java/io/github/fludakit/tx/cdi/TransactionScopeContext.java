package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.transaction.TransactionScoped;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDI {@link jakarta.enterprise.context.spi.Context} implementation for
 * {@link TransactionScoped @TransactionScoped} beans.
 *
 * <p>Bean instances live in the current {@link TransactionStatus}, so their lifecycle is tied to
 * the transaction: the store is created on first access and destroyed when the transaction
 * completes, whatever the outcome.</p>
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
 * <p>This context is registered by {@link TransactionalCdiExtension}. A joined or nested
 * participant shares the running transaction's attributes, so it sees the same bean store and
 * the store is destroyed once, at the real boundary.</p>
 *
 * @see TransactionScoped
 * @see TransactionalCdiExtension
 */
public class TransactionScopeContext implements jakarta.enterprise.context.spi.Context {

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
        return getBeanStore().get(contextual);
    }

    @Override
    public boolean isActive() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        // a completed status stays active for completion callbacks, so transaction-scoped beans
        // are still reachable from afterCommit/afterCompletion observers
        return status != null && status.isActualTransactionActive();
    }

    private BeanStore getBeanStore() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        if (status == null || !status.isActualTransactionActive()) {
            throw new IllegalStateException("No active transaction for @TransactionScoped bean");
        }

        BeanStore store = status.getAttribute(BeanStore.class);
        if (store != null) {
            return store;
        }
        if (status.isCompleted()) {
            // the store would normally already exist in this case; a completed status must not
            // accept new state
            throw new IllegalStateException("No active transaction for @TransactionScoped bean");
        }

        // atomic get-or-create: putAttributeIfAbsent lets only one caller win the race to own
        // this transaction's bean store, and only the winner registers transaction cleanup
        BeanStore candidate = new BeanStore();
        BeanStore bound = status.putAttributeIfAbsent(BeanStore.class, candidate);
        if (bound == null) {
            status.registerSynchronization(candidate);
            return candidate;
        }
        // lost the race — the winner owns the store and its cleanup
        return bound;
    }

    /**
     * Stores transaction-scoped bean instances and handles cleanup.
     */
    private static class BeanStore implements TransactionSynchronization {

        private final Map<Contextual<?>, InstanceInfo<?>> instances = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        <T> T get(Contextual<T> contextual) {
            InstanceInfo<?> info = instances.get(contextual);
            return info != null ? (T) info.instance : null;
        }

        <T> void put(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {
            instances.put(contextual, new InstanceInfo<>(instance, contextual, creationalContext));
        }

        @Override
        public void afterCompletion(Status status) {
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
