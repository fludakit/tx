package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;

/**
 * Pluggable strategy for deciding whether a failed transactional execution should roll back.
 *
 * <p>Two built-in strategies cover the two front-ends:</p>
 * <ul>
 *   <li>{@link RollbackDecisionStrategy#alwaysOnFailure()} — roll back on any failure (used by
 *       {@code DefaultTransactionalOperations})</li>
 *   <li>{@link RollbackDecisionStrategy#classified()} — roll back only when the failure matches
 *       the definition's {@code rollbackOn}/{@code dontRollbackOn} rules (used by
 *       {@code TransactionalInterceptor})</li>
 * </ul>
 *
 * @see TransactionTemplate
 */
@FunctionalInterface
public interface RollbackDecisionStrategy {

    /**
     * Always roll back on any non-null failure. Used by the programmatic operations front-end,
     * where any exception escaping the unit of work should roll back the transaction.
     */
    static RollbackDecisionStrategy alwaysOnFailure() {
        return (failure, definition) -> failure != null;
    }

    /**
     * Classify the failure using the definition's {@code rollbackOn}/{@code dontRollbackOn}
     * rules, falling back to {@code RuntimeException} or {@code Error}. Used by the CDI
     * interceptor front-end, which must follow the spec's rollback rules.
     *
     * <p>The failure's cause chain is unwrapped through {@link
     * java.lang.reflect.InvocationTargetException} and {@link
     * java.util.concurrent.ExecutionException} before classification. The transaction definition
     * is supplied at {@link #shouldRollback(Throwable, TransactionDefinition)} call time.</p>
     */
    static RollbackDecisionStrategy classified() {
        return (failure, definition) -> {
            if (failure == null) {
                return false;
            }
            Throwable candidate = failure;
            while (candidate.getCause() != null
                    && (candidate instanceof java.lang.reflect.InvocationTargetException
                            || candidate instanceof java.util.concurrent.ExecutionException)) {
                candidate = candidate.getCause();
            }
            for (Class<? extends Throwable> type : definition.dontRollbackOn()) {
                if (type.isAssignableFrom(candidate.getClass())) {
                    return false;
                }
            }
            for (Class<? extends Throwable> type : definition.rollbackOn()) {
                if (type.isAssignableFrom(candidate.getClass())) {
                    return true;
                }
            }
            return candidate instanceof RuntimeException || candidate instanceof Error;
        };
    }

    /**
     * Decide whether a failed execution should roll back.
     *
     * @param failure    the failure thrown by the unit of work, or {@code null} if it completed
     *                   normally
     * @param definition the transaction definition carrying the rollback rules
     * @return {@code true} if the transaction should be rolled back
     */
    boolean shouldRollback(Throwable failure, TransactionDefinition definition);
}
