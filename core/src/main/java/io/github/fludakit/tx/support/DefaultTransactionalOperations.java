package io.github.fludakit.tx.support;

import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;

import jakarta.transaction.Transactional;

import java.util.Objects;

/**
 * Default {@link TransactionalOperations} implementation backed by a {@link PlatformTransactionManager}.
 *
 * <p>This class provides programmatic transaction management, mirroring the propagation logic from
 * the CDI {@code TransactionalInterceptor} but without requiring CDI. It resolves
 * {@link Transactional.TxType} propagation rules and drives the transaction lifecycle
 * (begin, bind, execute, commit/rollback).</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
 * TransactionalOperations txOps = new DefaultTransactionalOperations(txManager);
 *
 * Long userId = txOps.execute(ctx -> {
 *     Connection conn = (Connection) ctx.getResource(dataSource);
 *     // ... JDBC operations ...
 *     return userId;
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
 * @see TransactionalOperations
 * @see PlatformTransactionManager
 */
public class DefaultTransactionalOperations implements TransactionalOperations {

    private final PlatformTransactionManager transactionManager;

    public DefaultTransactionalOperations(PlatformTransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    public <T> T execute(TransactionDefinition definition, TransactionExecutor<T> callback) throws Exception {
        Transactional.TxType propagation = definition.propagation();
        TransactionContext ctx = TransactionContextHolder.get();
        boolean active = ctx != null && ctx.isActualTransactionActive();

        return switch (propagation) {
            case REQUIRED -> active ? callback.call(ctx) : executeNew(definition, callback);
            case REQUIRES_NEW -> executeNew(definition, callback);
            case SUPPORTS -> active ? callback.call(ctx) : callback.call(TransactionContext.NON_TRANSACTIONAL);
            case NOT_SUPPORTED -> active
                    ? TransactionContextHolder.call(TransactionContext.NON_TRANSACTIONAL, () -> callback.call(TransactionContext.NON_TRANSACTIONAL))
                    : callback.call(TransactionContext.NON_TRANSACTIONAL);
            case MANDATORY -> {
                if (!active) {
                    throw new TransactionException("No active transaction (MANDATORY)");
                }
                yield callback.call(ctx);
            }
            case NEVER -> {
                if (active) {
                    throw new TransactionException("Active transaction not allowed (NEVER)");
                }
                yield callback.call(TransactionContext.NON_TRANSACTIONAL);
            }
        };
    }

    private <T> T executeNew(TransactionDefinition definition, TransactionExecutor<T> callback) throws Exception {
        TransactionContext context = transactionManager.getTransaction(definition);
        return TransactionContextHolder.call(context, () -> {
            try {
                T result = callback.call(context);
                complete(context, null);
                return result;
            } catch (Throwable failure) {
                complete(context, failure);
                throw rethrow(failure);
            }
        });
    }

    private void complete(TransactionContext context, Throwable failure) {
        if (context.isCompleted()) {
            return;
        }
        if (context.isRollbackOnly() || failure != null) {
            rollbackTransaction(context);
        } else {
            commitTransaction(context);
        }
    }

    private void commitTransaction(TransactionContext context) {
        context.triggerBeforeCommit(false);
        context.triggerBeforeCompletion();
        try {
            transactionManager.commit(context);
        } catch (RuntimeException ex) {
            context.triggerAfterCompletion(TransactionCallback.CompletionStatus.UNKNOWN);
            context.markCompleted();
            throw ex;
        }
        context.triggerAfterCommit();
        context.triggerAfterCompletion(TransactionCallback.CompletionStatus.COMMITTED);
        context.markCompleted();
    }

    private void rollbackTransaction(TransactionContext context) {
        context.triggerBeforeCompletion();
        try {
            transactionManager.rollback(context);
        } catch (RuntimeException ex) {
            context.triggerAfterCompletion(TransactionCallback.CompletionStatus.UNKNOWN);
            context.markCompleted();
            throw ex;
        }
        context.triggerAfterCompletion(TransactionCallback.CompletionStatus.ROLLED_BACK);
        context.markCompleted();
    }

    private static Exception rethrow(Throwable throwable) throws Exception {
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        throw new RuntimeException(throwable);
    }
}
