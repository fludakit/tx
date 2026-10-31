package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionManager;

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
 * propagation/lifecycle state machine to {@link TransactionManager#execute}.
 *
 * <p>Bound to the internal {@link TransactionalBinding} marker (added by
 * {@link TransactionalCdiExtension} to every {@code @Transactional} type) so a single interceptor
 * matches all six {@code TxType} values. The annotation-to-{@link TransactionDefinition}
 * resolution happens once at deployment in the extension; this interceptor only looks the
 * precomputed definition up.</p>
 *
 * <p>The programmatic {@code execute} uses the same manager, so the two front-ends share one
 * lifecycle, one propagation table, and one set of rollback rules — this class contributes only
 * the declarative-specific piece: it installs a {@link TransactionEventReplay} on every
 * transaction it begins, so buffered CDI events reach the transactional observers that were
 * vetoed at deployment.</p>
 *
 * <p>The replay is registered only for a status this call <em>began</em>. A participant
 * ({@code REQUIRED}, {@code MANDATORY}, {@code SUPPORTS}) already has one at the boundary that
 * owns the transaction, and registering another would replay every event twice.</p>
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

    @AroundInvoke
    public Object manageTransaction(InvocationContext context) throws Exception {
        TransactionDefinition definition = resolve(context);
        if (definition == null) {
            return context.proceed();
        }
        // the resource/status handed to the action are irrelevant here: the intercepted method
        // resolves the ambient transaction itself
        return transactionManager.execute(definition, (resource, status) -> {
            if (status.isNewTransaction()) {
                status.registerSynchronization(new TransactionEventReplay(status, observerRegistry));
            }
            return context.proceed();
        });
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
