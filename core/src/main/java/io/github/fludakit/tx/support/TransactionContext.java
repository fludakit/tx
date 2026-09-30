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
