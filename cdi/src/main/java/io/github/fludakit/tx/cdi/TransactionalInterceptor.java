package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import io.github.fludakit.tx.support.TransactionCallback;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.transaction.Transactional;

/**
 * CDI interceptor honoring {@code jakarta.transaction.Transactional}: dispatches on the resolved
 * {@code TxType} and drives the resource-local transaction lifecycle (begin, bind, run, complete,
 * fire phase events).
 *
 * <p>Bound to the internal {@link TransactionalBinding} marker (added by
 * {@link TransactionalCdiExtension} to every {@code @Transactional} type) so a single interceptor
 * matches all six {@code TxType} values. The annotation-to-{@link TransactionDefinition}
 * resolution happens once at deployment in the extension; this interceptor only looks the
 * precomputed definition up.</p>
 */
@Interceptor
@TransactionalBinding
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 10)
public class TransactionalInterceptor {

    @Inject
    private TransactionManager<?> transactionManager;

    @Inject
    private TransactionalObserverRegistry observerRegistry;

    @Inject
    private TransactionalMetadataRegistry metadata;

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    // --- lifecycle ---

    @AroundInvoke
    public Object manageTransaction(InvocationContext context) throws Exception {
        TransactionDefinition definition = resolve(context);
        if (definition == null) {
            return context.proceed();
        }

        Transactional.TxType type = definition.propagation();
        TransactionContext ctx = TransactionContextHolder.get();
        boolean active = ctx != null && ctx.isActualTransactionActive();

        return switch (type) {
            case MANDATORY -> {
                if (!active) {
                    throw new TransactionException("No active transaction (MANDATORY)");
                }
                yield context.proceed();
            }
            case NEVER -> {
                if (active) {
                    throw new TransactionException("Active transaction not allowed (NEVER)");
                }
                yield context.proceed();
            }
            case SUPPORTS -> context.proceed();
            case NOT_SUPPORTED -> TransactionContextHolder.call(TransactionContext.NON_TRANSACTIONAL, context::proceed);
            case REQUIRES_NEW -> executeNew(context, definition);
            case REQUIRED -> active ? context.proceed() : executeNew(context, definition);
        };
    }

    private Object executeNew(InvocationContext invocation, TransactionDefinition definition) throws Exception {
        TransactionContext context = transactionManager.getTransaction(definition);
        context.registerCallback(new TransactionEventReplay(context, observerRegistry));
        return TransactionContextHolder.call(context, () -> {
            try {
                Object result = invocation.proceed();
                complete(context, definition, null);
                return result;
            } catch (Throwable failure) {
                complete(context, definition, failure);
                throw sneakyThrow(failure);
            }
        });
    }

    private void complete(TransactionContext context, TransactionDefinition definition, Throwable failure) {
        if (context.isCompleted()) {
            return;
        }
        boolean rollback = context.isRollbackOnly() || (failure != null && shouldRollback(failure, definition));
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

    // --- definition lookup ---

    private boolean shouldRollback(Throwable failure, TransactionDefinition definition) {
        Throwable candidate = failure;
        while (candidate.getCause() != null
                && (candidate instanceof InvocationTargetException || candidate instanceof ExecutionException)) {
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
    }

    private TransactionDefinition resolve(InvocationContext context) {
        Method method = context.getMethod();
        if (method == null) {
            return null;
        }
        Class<?> targetClass = context.getTarget() != null
                ? context.getTarget().getClass()
                : method.getDeclaringClass();
        return metadata.definitionFor(targetClass, method);
    }
}
