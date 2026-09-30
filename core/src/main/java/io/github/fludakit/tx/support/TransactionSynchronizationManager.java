package io.github.fludakit.tx.support;

import java.util.List;

/**
 * Static facade over the thread/scope-bound transaction state, mirroring Spring's
 * {@code TransactionSynchronizationManager}: binds resources keyed by owner identity, registers
 * synchronizations, and triggers them at the commit/rollback phases.
 *
 * <p>All state lives in the single {@link TransactionContextHolder}; this facade is the only place
 * it is mutated.</p>
 */
public final class TransactionSynchronizationManager {

    private TransactionSynchronizationManager() {
    }

    public static boolean isActualTransactionActive() {
        TransactionContext context = TransactionContextHolder.get();
        return context != null && context.isActualTransactionActive();
    }

    public static Object getResource(Object key) {
        TransactionContext context = TransactionContextHolder.get();
        return context == null ? null : context.getResources().get(key);
    }

    /**
     * Finds the first resource bound to the current transaction that matches the given type.
     *
     * <p>This is useful when the resource key is not available (e.g., CDI proxy scenarios)
     * or when searching by type is more convenient than by key.</p>
     *
     * @param type the type of resource to find
     * @return the resource, or {@code null} if no matching resource is bound
     */
    public static <T> T findResourceByType(Class<T> type) {
        TransactionContext context = TransactionContextHolder.get();
        if (context == null) {
            return null;
        }
        for (Object value : context.getResources().values()) {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        }
        return null;
    }

    public static void bindResource(Object key, Object value) {
        activeContext().getResources().put(key, value);
    }

    public static Object unbindResource(Object key) {
        return activeContext().getResources().remove(key);
    }

    public static void registerSynchronization(TransactionSynchronization synchronization) {
        activeContext().getSynchronizations().add(synchronization);
    }

    public static List<TransactionSynchronization> getSynchronizations() {
        TransactionContext context = TransactionContextHolder.get();
        return context == null ? List.of() : List.copyOf(context.getSynchronizations());
    }

    public static void setRollbackOnly() {
        activeContext().setRollbackOnly();
    }

    public static boolean isRollbackOnly() {
        TransactionContext context = TransactionContextHolder.get();
        return context != null && context.isRollbackOnly();
    }

    public static void triggerBeforeCommit(boolean readOnly) {
        for (TransactionSynchronization synchronization : getSynchronizations()) {
            synchronization.beforeCommit(readOnly);
        }
    }

    public static void triggerBeforeCompletion() {
        for (TransactionSynchronization synchronization : getSynchronizations()) {
            synchronization.beforeCompletion();
        }
    }

    public static void triggerAfterCommit() {
        for (TransactionSynchronization synchronization : getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    public static void triggerAfterCompletion(TransactionSynchronization.CompletionStatus status) {
        for (TransactionSynchronization synchronization : getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }

    private static TransactionContext activeContext() {
        TransactionContext context = TransactionContextHolder.get();
        if (context == null || !context.isActualTransactionActive()) {
            throw new IllegalStateException("No transaction is active");
        }
        return context;
    }
}
