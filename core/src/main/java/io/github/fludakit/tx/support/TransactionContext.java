package io.github.fludakit.tx.support;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-transaction context/status — the single object that captures one resource-local
 * transaction.
 *
 * <p>Created and populated by a {@link io.github.fludakit.tx.PlatformTransactionManager}, bound
 * to the thread/scope by {@link TransactionContextHolder}. It holds the bound
 * resources, the registered callbacks, the deferred event payloads, and the rollback-only
 * and completed flags.</p>
 *
 * <h2>Resource Storage</h2>
 * <p>Resources are stored in an {@link IdentityHashMap} keyed by owner identity (e.g., DataSource
 * or EntityManagerFactory instances). Identity-based storage prevents accidental collisions when
 * multiple resource owners are in use.</p>
 *
 * <h3>CDI Proxy Consideration</h3>
 * <p>CDI client proxies (injected for {@code @ApplicationScoped} beans) are different object
 * instances from the raw beans. Since {@code IdentityHashMap} uses {@code ==} for key comparison,
 * looking up a resource with a proxy key fails even though the raw instance was used as the key.</p>
 *
 * <p><b>Why not ConcurrentHashMap?</b> CDI proxies don't delegate {@code equals()} — they use the
 * default {@code Object.equals()} which is identity-based. So {@code proxyEmf.equals(rawEmf)}
 * returns {@code false}, and equality-based maps don't help.</p>
 *
 * <p><b>Workaround:</b> Use {@link #findResourceByType(Class)} to search by value instead of key.
 * This works for single-resource scenarios but cannot distinguish between multiple resources of
 * the same type.</p>
 *
 * <h2>Usage Patterns</h2>
 * <pre>{@code
 * // Store resource (in PlatformTransactionManager.getTransaction())
 * TransactionContext ctx = new TransactionContext(true);
 * ctx.bindResource(dataSource, connection);
 * ctx.registerCallback(cleanupCallback);
 *
 * // Retrieve resource by key (works with raw instance)
 * Connection conn = (Connection) ctx.getResource(dataSource);
 *
 * // Retrieve resource by type (CDI proxy-safe, single-resource only)
 * EntityManager em = ctx.findResourceByType(EntityManager.class);
 * }</pre>
 */
public final class TransactionContext {

    /**
     * Shared context representing "no active transaction" (used by NOT_SUPPORTED/NEVER/SUPPORTS).
     */
    public static final TransactionContext NON_TRANSACTIONAL = new TransactionContext(false);

    private final boolean actualTransactionActive;
    private final Map<Object, Object> resources = new IdentityHashMap<>();
    private final List<TransactionCallback> callbacks = new ArrayList<>();
    private final TransactionEventStore eventStore = new TransactionEventStore();
    private volatile boolean rollbackOnly;
    private volatile boolean completed;

    public TransactionContext(boolean actualTransactionActive) {
        this.actualTransactionActive = actualTransactionActive;
    }

    public boolean isActualTransactionActive() {
        return actualTransactionActive;
    }

    public Object getResource(Object key) {
        return resources.get(key);
    }

    public void bindResource(Object key, Object value) {
        resources.put(key, value);
    }

    public Object unbindResource(Object key) {
        return resources.remove(key);
    }

    /**
     * Finds the first resource bound to the current transaction that matches the given type.
     *
     * <p>This method searches by value (using {@code instanceof}) instead of by key, which makes
     * it safe for CDI proxy scenarios where the proxy instance differs from the raw instance used
     * as the key.</p>
     *
     * <h3>Limitations</h3>
     * <ul>
     *   <li><b>Single-resource only:</b> If multiple resources of the same type are bound (e.g.,
     *       multiple EntityManagers in a multi-EMF scenario), this method returns the first match
     *       with no guarantee about which one.</li>
     *   <li><b>Performance:</b> O(n) iteration instead of O(1) hash lookup.</li>
     * </ul>
     *
     * <h3>When to use</h3>
     * <ul>
     *   <li>CDI proxy scenarios where the raw instance is not available</li>
     *   <li>Single-resource scenarios (one DataSource, one EntityManagerFactory)</li>
     *   <li>When you don't have a reference to the resource owner (key)</li>
     * </ul>
     *
     * <h3>When NOT to use</h3>
     * <ul>
     *   <li>Multi-resource scenarios (multiple DataSources or EntityManagerFactories)</li>
     *   <li>When you need a specific resource instance (use {@link #getResource(Object)} instead)</li>
     * </ul>
     *
     * @param type the type of resource to find
     * @return the first matching resource, or {@code null} if no matching resource is bound
     */
    public <T> T findResourceByType(Class<T> type) {
        for (Object value : resources.values()) {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        }
        return null;
    }

    public void registerCallback(TransactionCallback callback) {
        callbacks.add(callback);
    }

    public Map<Object, Object> getResources() {
        return resources;
    }

    public List<TransactionCallback> getCallbacks() {
        return callbacks;
    }

    public void triggerBeforeCommit(boolean readOnly) {
        for (TransactionCallback callback : callbacks) {
            callback.beforeCommit(readOnly);
        }
    }

    public void triggerBeforeCompletion() {
        for (TransactionCallback callback : callbacks) {
            callback.beforeCompletion();
        }
    }

    public void triggerAfterCommit() {
        for (TransactionCallback callback : callbacks) {
            callback.afterCommit();
        }
    }

    public void triggerAfterCompletion(TransactionCallback.CompletionStatus status) {
        for (TransactionCallback callback : callbacks) {
            callback.afterCompletion(status);
        }
    }

    public TransactionEventStore getEventStore() {
        return eventStore;
    }

    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void markCompleted() {
        this.completed = true;
    }
}
