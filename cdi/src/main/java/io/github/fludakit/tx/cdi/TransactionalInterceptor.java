package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.support.RollbackDecisionStrategy;
import io.github.fludakit.tx.support.TransactionTemplate;

import java.lang.reflect.Method;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.transaction.Transactional;

/**
 * CDI interceptor honoring {@code jakarta.transaction.Transactional}: resolves the
 * {@link TransactionDefinition} for the intercepted method and delegates the whole
 * propagation/lifecycle state machine to {@link TransactionTemplate}.
 *
 * <p>Bound to the internal {@link TransactionalBinding} marker (added by
 * {@link TransactionalCdiExtension} to every {@code @Transactional} type) so a single interceptor
 * matches all six {@code TxType} values. The annotation-to-{@link TransactionDefinition}
 * resolution happens once at deployment in the extension; this interceptor only looks the
 * precomputed definition up.</p>
 *
 * <p>The execution lifecycle (propagation dispatch, begin, bind, run, complete, cleanup) is owned
 * by {@link TransactionTemplate} — the same template {@code DefaultTransactionalOperations} uses,
 * so the programmatic and declarative front-ends can never drift apart. This class contributes
 * only the two front-end specific pieces:</p>
 * <ul>
 *   <li>a {@link RollbackDecisionStrategy#classified()} strategy, so rollback follows the
 *       definition's {@code rollbackOn}/{@code dontRollbackOn} rules per the spec</li>
 *   <li>a callback registrar that installs a {@link TransactionEventReplay} on every new
 *       transaction, so buffered CDI events reach transactional observers</li>
 * </ul>
 *
 * <p>The template is created lazily on first use because Weld injects {@link TransactionManager}
 * and {@link TransactionalObserverRegistry} after construction, so they are unavailable in a
 * field initializer.</p>
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
    private TransactionTemplate<Object> template() {
        TransactionTemplate<Object> current = template;
        if (current == null) {
            synchronized (this) {
                current = template;
                if (current == null) {
                    current = new TransactionTemplate<>((TransactionManager<Object>) transactionManager,
                            RollbackDecisionStrategy.classified(),
                            context -> context.registerCallback(
                                    new TransactionEventReplay(context, observerRegistry)));
                    template = current;
                }
            }
        }
        return current;
    }

    @AroundInvoke
    public Object manageTransaction(InvocationContext context) throws Exception {
        TransactionDefinition definition = resolve(context);
        if (definition == null) {
            return context.proceed();
        }
        // the resource/status handed to the action are irrelevant here: the intercepted method
        // resolves the ambient transaction itself
        return template().execute(definition, (resource, status) -> context.proceed());
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
