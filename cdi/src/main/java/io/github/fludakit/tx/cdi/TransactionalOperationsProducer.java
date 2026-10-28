package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionalOperations;
import io.github.fludakit.tx.support.DefaultTransactionalOperations;

import jakarta.enterprise.inject.Produces;

/**
 * CDI producer for {@link TransactionalOperations}, resolved per injection point from the
 * application-provided {@link TransactionManager}.
 *
 * <p>CDI resolves the producer's type parameter per injection point, so applications inject the
 * {@code TransactionalOperations} matching their manager's resource type:</p>
 *
 * <pre>{@code
 * @Inject TransactionalOperations<DataSource> txOps;       // DataSourceTransactionManager
 * @Inject TransactionalOperations<EntityManager> jpaOps;   // JpaTransactionManager
 *
 * public void doWork() {
 *     txOps.execute((ds, status) -> {
 *         try (Connection c = ds.getConnection()) {
 *             // joins the active transaction
 *         }
 *         return result;
 *     });
 * }
 * }</pre>
 *
 * @see TransactionalOperations
 * @see DefaultTransactionalOperations
 */
public class TransactionalOperationsProducer {

    @Produces
    public <T> TransactionalOperations<T> transactionalOperations(TransactionManager<T> transactionManager) {
        return new DefaultTransactionalOperations<>(transactionManager);
    }
}
