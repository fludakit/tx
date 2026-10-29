package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.support.CallbackRegistrar;
import io.github.fludakit.tx.support.RollbackDecisionStrategy;
import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import io.github.fludakit.tx.support.TransactionStatus;
import io.github.fludakit.tx.support.TransactionTemplate;

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
 *
 * <p>The execution lifecycle (begin, bind, run, complete, cleanup) is owned by
 * {@link TransactionTemplate}. This class only adapts the CDI
 * {@link InvocationContext#proceed()} callback to the template's action interface, registers a
 * {@link TransactionEventReplay} callback via the {@link CallbackRegistrar} hook, and uses the
 * {@link RollbackDecisionStrategy#classified()} strategy: rollback follows the definition's
 * {@code rollbackOn}/{@code dontRollbackOn} rules per the spec.</p>
 *
 * <p>The template is created lazily on first use because Weld injects
 * {@link TransactionManager} and {@link TransactionalObserverRegistry} after construction, so
 * they are unavailable in a field initializer.</p>
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

    private volatile TransactionTemplate<Object> template;

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private TransactionTemplate<Object> template() {
        TransactionTemplate<Object> t = template;
        if (t == null) {
            synchronized (this) {
                t = template;
                if (t == null) {
                    t = new TransactionTemplate<>((TransactionManager<Object>) transactionManager,
                            RollbackDecisionStrategy.classified(),
                            txContext -> txContext.registerCallback(
                                    new TransactionEventReplay(txContext, observerRegistry)));
                    template = t;
                }
            }
        }
        return t;
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
            case REQUIRES_NEW -> template().execute(definition,
                    (resource, status) -> context.proceed());
            case REQUIRED -> active ? context.proceed() : template().execute(definition,
                    (resource, status) -> context.proceed());
        };
    }

    // --- definition lookup ---

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
