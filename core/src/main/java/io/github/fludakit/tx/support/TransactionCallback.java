package io.github.fludakit.tx.support;

/**
 * Callback interface for transaction lifecycle events.
 *
 * <p>Implementations can register for notifications at various phases of the transaction lifecycle.
 * This is useful for resource cleanup, cache synchronization, or other side effects that must be
 * coordinated with transaction completion.</p>
 *
 * <h2>Lifecycle Phases</h2>
 * <p>The callback methods are invoked in the following order:</p>
 * <ol>
 *   <li><b>Before commit:</b> {@link #beforeCommit(boolean)} — invoked before the transaction is
 *       committed. Use this for final validation or flushing pending changes.</li>
 *   <li><b>Before completion:</b> {@link #beforeCompletion()} — invoked after {@code beforeCommit}
 *       but before the actual commit/rollback. Use this for last-minute cleanup.</li>
 *   <li><b>After commit:</b> {@link #afterCommit()} — invoked after successful commit. Use this
 *       for cache updates, event publishing, or other post-commit actions.</li>
 *   <li><b>After completion:</b> {@link #afterCompletion(CompletionStatus)} — invoked after the
 *       transaction has completed (committed or rolled back). Use this for resource cleanup.</li>
 * </ol>
 *
 * <h2>Registration</h2>
 * <p>Callbacks are registered via {@link TransactionContext#registerCallback(TransactionCallback)}.
 * They are typically registered by {@link io.github.fludakit.tx.PlatformTransactionManager}
 * implementations when resources are bound to the transaction context.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class CacheCleanupCallback implements TransactionCallback {
 *     private final Cache cache;
 *     
 *     @Override
 *     public void afterCompletion(CompletionStatus status) {
 *         if (status == CompletionStatus.COMMITTED) {
 *             cache.invalidateAll();
 *         }
 *     }
 * }
 * 
 * // Register in PlatformTransactionManager.getTransaction()
 * context.registerCallback(new CacheCleanupCallback(cache));
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Callback methods are invoked on the thread that commits or rolls back the transaction.
 * Implementations should be thread-safe if they access shared state.</p>
 *
 * @see TransactionContext#registerCallback(TransactionCallback)
 * @see TransactionContext#triggerBeforeCommit(boolean)
 * @see TransactionContext#triggerAfterCompletion(CompletionStatus)
 */
public interface TransactionCallback {
    
    /**
     * Invoked before the transaction is committed.
     *
     * <p>This is the last chance to perform validation or flush pending changes before the
     * transaction is committed. Exceptions thrown from this method will prevent the commit.</p>
     *
     * @param readOnly {@code true} if the transaction is read-only
     */
    default void beforeCommit(boolean readOnly) {
    }

    /**
     * Invoked before the transaction completes (commit or rollback).
     *
     * <p>This is invoked after {@link #beforeCommit(boolean)} but before the actual commit or
     * rollback operation. Use this for last-minute cleanup or state preparation.</p>
     */
    default void beforeCompletion() {
    }

    /**
     * Invoked after the transaction has been successfully committed.
     *
     * <p>Use this for post-commit actions such as cache updates, event publishing, or notifying
     * other systems. This method is only called if the transaction committed successfully.</p>
     */
    default void afterCommit() {
    }

    /**
     * Invoked after the transaction has completed (committed or rolled back).
     *
     * <p>This is the final callback in the transaction lifecycle. Use this for resource cleanup
     * that must happen regardless of the transaction outcome.</p>
     *
     * @param status the completion status (committed, rolled back, or unknown)
     */
    default void afterCompletion(CompletionStatus status) {
    }

    /**
     * Transaction completion status.
     */
    enum CompletionStatus {
        /** Transaction was successfully committed. */
        COMMITTED,
        
        /** Transaction was rolled back. */
        ROLLED_BACK,
        
        /** Transaction outcome is unknown (e.g., due to a system error). */
        UNKNOWN
    }
}
