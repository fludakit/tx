package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.support.DefaultTransactionalOperations;
import io.github.fludakit.tx.support.TransactionalOperations;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer for {@link TransactionalOperations}.
 *
 * <p>Produces an {@link ApplicationScoped} {@link TransactionalOperations} backed by the
 * application-provided {@link PlatformTransactionManager}. This enables programmatic transaction
 * management via injection:</p>
 *
 * <pre>{@code
 * @Inject TransactionalOperations txOps;
 *
 * public void doWork() {
 *     txOps.execute(ctx -> {
 *         // ... transactional work ...
 *         return result;
 *     });
 * }
 * }</pre>
 *
 * @see TransactionalOperations
 * @see DefaultTransactionalOperations
 */
@ApplicationScoped
public class TransactionalOperationsProducer {

    @Inject
    private PlatformTransactionManager transactionManager;

    @Produces
    @ApplicationScoped
    public TransactionalOperations transactionalOperations() {
        return new DefaultTransactionalOperations(transactionManager);
    }
}
