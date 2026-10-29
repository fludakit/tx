package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionExecution;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionalOperations;

import jakarta.transaction.Transactional;

import java.util.Objects;

/**
 * Default {@link TransactionalOperations} implementation backed by a {@link TransactionManager},
 * handing the transaction's backing resource to every execute callback.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * TransactionManager<Connection> txManager = new JdbcTransactionManager(dataSource);
 * TransactionalOperations<Connection> txOps = new DefaultTransactionalOperations<>(txManager);
 *
 * List<User> users = txOps.execute(conn -> {
 *     // conn is the transactional Connection bound to the active transaction
 *     // ... JDBC operations ...
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
 * <p>The execution lifecycle (begin, bind, run, complete, cleanup) is owned by
 * {@link TransactionTemplate}. This class only adapts the programmatic
 * {@link TransactionExecution} callback to the template's action interface and uses the
 * {@link RollbackDecisionStrategy#alwaysOnFailure()} strategy: any exception escaping the unit
 * of work rolls back the transaction.</p>
 *
 * @param <T> the backing resource type handed to the execute callback
 * @see TransactionalOperations
 * @see TransactionManager
 * @see TransactionTemplate
 */
public class DefaultTransactionalOperations<T> implements TransactionalOperations<T> {

    private final TransactionManager<T> transactionManager;
    private final TransactionTemplate<T> template;

    public DefaultTransactionalOperations(TransactionManager<T> transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.template = new TransactionTemplate<>(transactionManager,
                RollbackDecisionStrategy.alwaysOnFailure(),
                CallbackRegistrar.NOOP);
    }

    public TransactionManager<T> getTransactionManager() {
        return transactionManager;
    }

    @Override
    public <R> R execute(TransactionDefinition definition, TransactionExecution<T, R> execution) throws Exception {
        return template.execute(definition, execution);
    }

    @Override
    public <R> R executeNested(TransactionExecution<T, R> execution) throws Exception {
        return template.executeNested(execution);
    }
}
