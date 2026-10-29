package io.github.fludakit.tx.support;

/**
 * Callback-registrar hook for front-ends that need to register transaction lifecycle callbacks
 * when a new transaction begins.
 *
 * <p>The {@link TransactionalInterceptor} uses this to register a
 * {@link io.github.fludakit.tx.cdi.TransactionEventReplay} callback on every new transaction,
 * while {@link DefaultTransactionalOperations} uses a no-op registrar (its callbacks come from
 * the {@link TransactionManager} itself).</p>
 */
@FunctionalInterface
public interface CallbackRegistrar {

    /** No-op registrar — used by {@code DefaultTransactionalOperations}. */
    CallbackRegistrar NOOP = context -> {
    };

    /**
     * Register front-end-specific callbacks on the given transaction context.
     *
     * @param context the transaction context for the newly begun transaction
     */
    void register(TransactionContext context);
}
