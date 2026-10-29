package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionExecution;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionalOperations;

import jakarta.transaction.Transactional;

import java.util.Objects;

/**
 * Default {@link TransactionalOperations} implementation backed by a {@link TransactionManager},
 * handing the transaction's backing resource to every execute callback.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * TransactionManager<DataSource> txManager = new JdbcTransactionManager(dataSource);
 * TransactionalOperations<DataSource> txOps = new DefaultTransactionalOperations<>(txManager);
 *
 * List<User> users = txOps.execute(ds -> {
 *     try (Connection conn = ds.getConnection()) {   // joins the active transaction
 *         // ... JDBC operations ...
 *     }
 * });
 * }</pre>
 *
 * <h2>Propagation Support</h2>
 * <p>All six {@link Transactional.TxType} values are supported:</p>
 * <ul>
 *   <li>{@code REQUIRED}: Join existing or create new (default)</li>
 *   <li>{@code REQUIRES_NEW}: Always create new, suspending existing</li>
 *   <li>{@code SUPPORTS}: Join existing or execute non-transactionally</li>
 *   <li>{@code NOT_SUPPORTED}: Execute non-transactionally, suspending existing</li>
 *   <li>{@code MANDATORY}: Require existing transaction</li>
 *   <li>{@code NEVER}: Require no existing transaction</li>
 * </ul>
 *
 * @param <T> the backing resource type handed to the execute callback
 * @see TransactionalOperations
 * @see TransactionManager
 */
public class DefaultTransactionalOperations<T> implements TransactionalOperations<T> {

    private final TransactionManager<T> transactionManager;

    public DefaultTransactionalOperations(TransactionManager<T> transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    public TransactionManager<T> getTransactionManager() {
        return transactionManager;
    }

    @Override
    public <R> R execute(TransactionDefinition definition, TransactionExecution<T, R> execution) throws Exception {
        Transactional.TxType propagation = definition.propagation();
        TransactionContext ctx = TransactionContextHolder.get();
        boolean active = ctx != null && ctx.isActualTransactionActive();

        return switch (propagation) {
            case REQUIRED -> active ? execution.call(transactionManager.getResource(), ctx.getStatus()) : executeNew(definition, execution);
            case REQUIRES_NEW -> executeNew(definition, execution);
            case SUPPORTS -> active
                    ? execution.call(transactionManager.getResource(), ctx.getStatus())
                    : execution.call(transactionManager.getResource(), TransactionContext.NON_TRANSACTIONAL.getStatus());
            case NOT_SUPPORTED -> active
                    ? TransactionContextHolder.call(TransactionContext.NON_TRANSACTIONAL,
                            () -> execution.call(transactionManager.getResource(), TransactionContext.NON_TRANSACTIONAL.getStatus()))
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

    @Override
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

    private <R> R executeNew(TransactionDefinition definition, TransactionExecution<T, R> execution) throws Exception {
        TransactionContext context = transactionManager.getTransaction(definition);
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
        if (context.isRollbackOnly() || failure != null) {
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
