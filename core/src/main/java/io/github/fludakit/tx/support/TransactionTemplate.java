package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionExecution;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionStatus;

import jakarta.transaction.Transactional;

import java.util.Objects;

/**
 * The unified transaction execution template.
 *
 * <p>Both transaction front-ends — {@link DefaultTransactionalOperations} (programmatic) and
 * the CDI {@code TransactionalInterceptor} (declarative {@code @Transactional}) — delegate to
 * this template for propagation and lifecycle. The template owns the begin/execute/complete
 * state machine so any future lifecycle change is made once.</p>
 *
 * <p>Lifecycle per transaction:</p>
 * <ol>
 *   <li>{@code begin}: {@link TransactionManager#getTransaction(TransactionDefinition)} opens and
 *       binds the context</li>
 *   <li>{@code execute}: the unit of work runs with the context bound</li>
 *   <li>{@code complete}: rollback-only or failure → rollback; otherwise commit</li>
 *   <li>{@code cleanup}: after-completion callbacks fire, the context is marked completed, and the
 *       thread binding is released</li>
 * </ol>
 *
 * <p>Two extension points let the front-ends customize behavior:</p>
 * <ul>
 *   <li>{@link RollbackDecisionStrategy} decides whether a failed execution rolls back</li>
 *   <li>{@link CallbackRegistrar} registers front-end callbacks on new transactions</li>
 * </ul>
 *
 * @param <T> the backing resource type
 * @see RollbackDecisionStrategy
 * @see CallbackRegistrar
 * @see DefaultTransactionalOperations
 */
public class TransactionTemplate<T> {

    private final TransactionManager<T> transactionManager;
    private final RollbackDecisionStrategy rollbackDecision;
    private final CallbackRegistrar callbackRegistrar;

    /**
     * @param transactionManager the transaction manager
     * @param rollbackDecision   decides whether a failed execution rolls back
     * @param callbackRegistrar  registers front-end callbacks on new transactions
     */
    public TransactionTemplate(TransactionManager<T> transactionManager,
                               RollbackDecisionStrategy rollbackDecision,
                               CallbackRegistrar callbackRegistrar) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.rollbackDecision = Objects.requireNonNull(rollbackDecision, "rollbackDecision must not be null");
        this.callbackRegistrar = Objects.requireNonNull(callbackRegistrar, "callbackRegistrar must not be null");
    }

    /**
     * Execute the given unit of work within a transaction boundary, respecting the definition's
     * propagation semantics.
     *
     * @param <R>        the return type
     * @param definition transaction definition (propagation, rollback rules, readOnly)
     * @param execution  the unit of work, receiving the backing resource and transaction status
     * @return the result of the unit of work
     * @throws Exception if the unit of work throws an exception
     */
    public <R> R execute(TransactionDefinition definition,
                         TransactionExecution<T, R> execution) throws Exception {
        Transactional.TxType propagation = definition.propagation();
        TransactionContext ctx = TransactionContextHolder.get();
        boolean active = ctx != null && ctx.isActualTransactionActive();

        return switch (propagation) {
            case REQUIRED -> active
                    ? execution.call(transactionManager.getResource(), ctx.getStatus())
                    : executeNew(definition, execution);
            case REQUIRES_NEW -> executeNew(definition, execution);
            case SUPPORTS -> active
                    ? execution.call(transactionManager.getResource(), ctx.getStatus())
                    : execution.call(transactionManager.getResource(), TransactionContext.NON_TRANSACTIONAL.getStatus());
            case NOT_SUPPORTED -> active
                    ? TransactionContextHolder.call(TransactionContext.NON_TRANSACTIONAL,
                            () -> execution.call(transactionManager.getResource(),
                                    TransactionContext.NON_TRANSACTIONAL.getStatus()))
                    : execution.call(transactionManager.getResource(), TransactionContext.NON_TRANSACTIONAL.getStatus());
            case MANDATORY -> {
                if (!active) {
                    throw new TransactionException("No active transaction (MANDATORY)");
                }
                yield execution.call(transactionManager.getResource(), ctx.getStatus());
            }
            case NEVER -> {
                if (active) {
                    throw new TransactionException("Active transaction not allowed (NEVER)");
                }
                yield execution.call(transactionManager.getResource(), TransactionContext.NON_TRANSACTIONAL.getStatus());
            }
        };
    }

    /**
     * Execute a nested unit of work inside the active transaction, scoped by a savepoint.
     *
     * @param <R>    the return type
     * @param execution the unit of work
     * @return the result of the unit of work
     * @throws Exception if the unit of work throws an exception
     * @throws UnsupportedOperationException if the transaction has no savepoint support
     */
    public <R> R executeNested(TransactionExecution<T, R> execution) throws Exception {
        TransactionContext ctx = TransactionContextHolder.get();
        boolean active = ctx != null && ctx.isActualTransactionActive();
        if (!active) {
            // nothing to nest into: a full transaction, no savepoint to scope
            return executeNew(TransactionDefinition.withDefaults(), execution);
        }
        TransactionStatus status = ctx.getStatus();
        TransactionSavepoint savepoint = status.createSavepoint();
        try {
            R result = execution.call(transactionManager.getResource(), status);
            status.releaseSavepoint(savepoint);
            return result;
        } catch (Throwable failure) {
            try {
                status.rollbackToSavepoint(savepoint);
            } catch (RuntimeException rollbackEx) {
                failure.addSuppressed(rollbackEx);
            }
            // the surrounding transaction stays active: a nested failure is scoped to the savepoint
            throw rethrow(failure);
        }
    }

    // --- lifecycle ---

    private <R> R executeNew(TransactionDefinition definition,
                             TransactionExecution<T, R> execution) throws Exception {
        TransactionContext context = transactionManager.getTransaction(definition);
        callbackRegistrar.register(context);
        return TransactionContextHolder.call(context, () -> {
            try {
                R result = execution.call(transactionManager.getResource(), context.getStatus());
                complete(context, definition, null);
                return result;
            } catch (Throwable failure) {
                complete(context, definition, failure);
                throw rethrow(failure);
            }
        });
    }

    private void complete(TransactionContext context, TransactionDefinition definition, Throwable failure) {
        if (context.isCompleted()) {
            return;
        }
        boolean rollback = context.isRollbackOnly()
                || (failure != null && rollbackDecision.shouldRollback(failure, definition));
        if (rollback) {
            rollbackTransaction(context);
        } else {
            commitTransaction(context, definition);
        }
    }

    private void commitTransaction(TransactionContext context, TransactionDefinition definition) {
        context.triggerBeforeCommit(definition.readOnly());
        context.triggerBeforeCompletion();
        try {
            transactionManager.commit(context);
        } catch (RuntimeException ex) {
            context.triggerAfterCompletion(TransactionCallback.CompletionStatus.UNKNOWN);
            context.markCompleted(TransactionCallback.CompletionStatus.UNKNOWN);
            throw ex;
        }
        context.triggerAfterCommit();
        context.triggerAfterCompletion(TransactionCallback.CompletionStatus.COMMITTED);
        context.markCompleted(TransactionCallback.CompletionStatus.COMMITTED);
    }

    private void rollbackTransaction(TransactionContext context) {
        context.triggerBeforeCompletion();
        try {
            transactionManager.rollback(context);
        } catch (RuntimeException ex) {
            context.triggerAfterCompletion(TransactionCallback.CompletionStatus.UNKNOWN);
            context.markCompleted(TransactionCallback.CompletionStatus.UNKNOWN);
            throw ex;
        }
        context.triggerAfterCompletion(TransactionCallback.CompletionStatus.ROLLED_BACK);
        context.markCompleted(TransactionCallback.CompletionStatus.ROLLED_BACK);
    }

    private static Exception rethrow(Throwable throwable) throws Exception {
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        // the only non-Exception Throwables are Errors — propagate them raw, never wrapped
        throw (Error) throwable;
    }
}
