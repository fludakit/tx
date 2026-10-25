package io.github.fludakit.tx.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-transaction context — the single object that captures one resource-local transaction.
 *
 * <p>Created and populated by a {@link io.github.fludakit.tx.TransactionManager}, bound to the
 * thread/scope by {@link TransactionContextHolder}. It holds the bound resources, the registered
 * callbacks, and the transaction's {@link TransactionStatus}.</p>
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
 * // Store resources (in TransactionManager.getTransaction())
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
     * resource, registering a callback, changing the status — would leak into all subsequent
     * non-transactional scopes, so each mutator throws {@link UnsupportedOperationException}
     * instead of silently poisoning the sentinel.</p>
     */
    public static final TransactionContext NON_TRANSACTIONAL = new TransactionContext(false, true);

    private final boolean actualTransactionActive;
    private final boolean immutable;
    private final Map<Class<?>, Object> resources = new LinkedHashMap<>();
    private final List<TransactionCallback> callbacks = new ArrayList<>();
    private final TransactionStatus status;

    public TransactionContext(boolean actualTransactionActive) {
        this.actualTransactionActive = actualTransactionActive;
        this.immutable = false;
        this.status = TransactionStatus.initial();
    }

    public TransactionContext(boolean actualTransactionActive, TransactionStatus status) {
        this.actualTransactionActive = actualTransactionActive;
        this.immutable = false;
        this.status = status == null ? TransactionStatus.initial() : status;
    }

    private TransactionContext(boolean actualTransactionActive, boolean immutable) {
        this.actualTransactionActive = actualTransactionActive;
        this.immutable = immutable;
        this.status = TransactionStatus.initial();
    }

    public boolean isActualTransactionActive() {
        return actualTransactionActive;
    }

    /** The transaction's status facts: read-only, new/joined, rollback-only, completion state. */
    public TransactionStatus getStatus() {
        return status;
    }

    /** Binds the transaction's resource under the given type key, replacing any previous binding. */
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

    public boolean isRollbackOnly() {
        return status.isRollbackOnly();
    }

    public void setRollbackOnly() {
        requireMutable("setRollbackOnly");
        status.setRollbackOnly();
    }

    public boolean isCompleted() {
        return status.isCompleted();
    }

    /**
     * Transitions the transaction to its terminal state — the same status the call site passes to
     * {@code triggerAfterCompletion}.
     */
    public void markCompleted(TransactionCallback.CompletionStatus completion) {
        requireMutable("markCompleted");
        status.markCompleted(toState(completion));
    }

    private static TransactionStatus.State toState(TransactionCallback.CompletionStatus completion) {
        return switch (completion) {
            case COMMITTED -> TransactionStatus.State.COMMITTED;
            case ROLLED_BACK -> TransactionStatus.State.ROLLED_BACK;
            case UNKNOWN -> TransactionStatus.State.UNKNOWN;
        };
    }

    private void requireMutable(String operation) {
        if (immutable) {
            throw new UnsupportedOperationException(
                    operation + " is not allowed on TransactionContext.NON_TRANSACTIONAL — the "
                            + "sentinel is shared by every non-transactional execution");
        }
    }
}
