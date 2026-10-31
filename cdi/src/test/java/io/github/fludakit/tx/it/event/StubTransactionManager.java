package io.github.fludakit.tx.it.event;

import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;
import jakarta.inject.Singleton;

/**
 * Minimal manager for event tests: it owns no resource and only needs the begin/commit/rollback
 * primitives so the shared template can drive the transaction lifecycle.
 */
@Singleton
public class StubTransactionManager extends AbstractTransactionOperations<String> {

    @Override
    protected void doBegin(DefaultTransactionStatus<String> status) {
        bindResource(status, "stub-resource");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus<String> status) {
        // phases are triggered by the template around this call; nothing to do here
    }

    @Override
    protected void doRollback(DefaultTransactionStatus<String> status) {
    }
}
