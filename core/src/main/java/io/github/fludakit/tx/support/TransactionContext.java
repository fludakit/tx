package io.github.fludakit.tx.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <p>One resource per type. There is no multi-datasource support — an application has one
 * transactional resource of each type, so the type IS the key and no owner instance needs to be
 * in hand. This also removes the CDI-proxy identity problem entirely: a proxy and its raw bean
 * share the same type.</p>
 *
 * <p>Keys and values: {@code Connection.class} → the transaction's physical connection (bound by
 * the JDBC manager, the proxy's lazy join, and the JPA manager's extraction alike, so mixed
 * JDBC/JPA code joins one transaction), {@code EntityManager.class} → the bound
 * {@code EntityManager}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Store resources (in PlatformTransactionManager.getTransaction())
 * TransactionContext ctx = new TransactionContext(true);
 * ctx.bindResource(Connection.class, connection);
 * ctx.registerCallback(cleanupCallback);
 *
 * // Retrieve a resource by type
 * Connection conn = ctx.getResource(Connection.class);
 * EntityManager em = ctx.getResource(EntityManager.class);
 * }</pre>
 */
public final class TransactionContext {

    /**
     * Shared context representing "no active transaction" (used by NOT_SUPPORTED/NEVER/SUPPORTS).
     *
     * <p>Immutable and shared by every execution on every thread: any mutation — binding a
     * resource, registering a callback, marking rollback-only — would leak into all subsequent
     * non-transactional scopes, so each mutator throws {@link UnsupportedOperationException}
     * instead of silently poisoning the sentinel.</p>
     */
    public static final TransactionContext NON_TRANSACTIONAL = new TransactionContext(false, true);

    private final boolean actualTransactionActive;
    private final boolean immutable;
    private final Map<Class<?>, Object> resources = new LinkedHashMap<>();
    private final List<TransactionCallback> callbacks = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private volatile boolean rollbackOnly;
    private volatile boolean completed;

    public TransactionContext(boolean actualTransactionActive) {
        this(actualTransactionActive, false);
    }

    private TransactionContext(boolean actualTransactionActive, boolean immutable) {
        this.actualTransactionActive = actualTransactionActive;
        this.immutable = immutable;
    }

    public boolean isActualTransactionActive() {
        return actualTransactionActive;
    }

    /**
     * Binds the transaction's resource under the given type key, replacing any previous binding.
     *
     * <p>The value's type need not match the key ({@code DataSource.class} deliberately holds the
     * transactional {@code Connection}); {@link #getResource(Class)} is the typed read.</p>
     */
    public void bindResource(Class<?> type, Object value) {
        requireMutable("bindResource");
        resources.put(type, value);
    }

    /** The transaction's resource of the given type, or {@code null} if none is bound. */
    public <T> T getResource(Class<T> type) {
        Object resource = resources.get(type);
        return resource == null ? null : type.cast(resource);
    }

    public void registerCallback(TransactionCallback callback) {
        requireMutable("registerCallback");
        callbacks.add(callback);
    }

    /** Unmodifiable view of the bound resources; use {@link #bindResource} to add. */
    public Map<Class<?>, Object> getResources() {
        return Collections.unmodifiableMap(resources);
    }

    /** Unmodifiable view of the registered callbacks; use {@link #registerCallback} to add. */
    public List<TransactionCallback> getCallbacks() {
        return Collections.unmodifiableList(callbacks);
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

    public void addEvent(Object event) {
        requireMutable("addEvent");
        events.add(event);
    }

    /** Unmodifiable view of the deferred event payloads; use {@link #addEvent} to add. */
    public List<Object> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    public void setRollbackOnly() {
        requireMutable("setRollbackOnly");
        this.rollbackOnly = true;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void markCompleted() {
        requireMutable("markCompleted");
        this.completed = true;
    }

    private void requireMutable(String operation) {
        if (immutable) {
            throw new UnsupportedOperationException(
                    operation + " is not allowed on TransactionContext.NON_TRANSACTIONAL — the "
                            + "sentinel is shared by every non-transactional execution");
        }
    }
}
