package io.github.fludakit.tx.it.lifecycle;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.cdi.TransactionalCdiExtension;
import io.github.fludakit.tx.cdi.TransactionalInterceptor;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives the interceptor against a stub {@link AbstractTransactionOperations} to verify that an
 * annotated call is bracketed by exactly one begin and one completion, and that a failing
 * resource primitive still produces one completion (exactly-once completion, rollback-only,
 * begin/commit/rollback failures).
 */
@ExtendWith(WeldJunit5Extension.class)
class TransactionLifecycleTest {

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            StubTransactionManager.class,
            LifecycleService.class,
            TransactionalInterceptor.class,
            TransactionalCdiExtension.class
    ).build();

    @Inject
    private StubTransactionManager manager;

    @Inject
    private LifecycleService service;

    @BeforeEach
    void reset() {
        manager.reset();
    }

    @Test
    void commitFailureStillCompletesExactlyOnce() {
        manager.commitFailure = new TransactionSystemException("boom", new RuntimeException("cause"));

        assertThrows(TransactionSystemException.class, service::succeeds);

        assertEquals(1, manager.beginCount);
        assertEquals(1, manager.commitCount);
        assertEquals(1, manager.rollbackCount, "a failed commit must trigger a best-effort rollback");
        assertEquals(1, manager.afterCompletionCount);
    }

    @Test
    void rollbackOnlyForcesRollback() {
        service.marksRollbackOnly();

        assertEquals(1, manager.beginCount);
        assertEquals(0, manager.commitCount);
        assertEquals(1, manager.rollbackCount);
        assertEquals(1, manager.afterCompletionCount);
    }

    @Test
    void beginFailureDoesNotComplete() {
        manager.beginFailure = new TransactionSystemException("begin", new RuntimeException("cause"));

        assertThrows(TransactionSystemException.class, service::succeeds);

        assertEquals(1, manager.beginCount);
        assertEquals(0, manager.commitCount);
        assertEquals(0, manager.rollbackCount);
        assertEquals(0, manager.afterCompletionCount);
    }

    @Test
    void rollbackFailureFiresUnknownCompletion() {
        manager.rollbackFailure = new TransactionSystemException("rollback", new RuntimeException("cause"));

        assertThrows(IllegalStateException.class, service::throwsRuntime);

        assertEquals(1, manager.rollbackCount);
        assertEquals(1, manager.afterCompletionCount);
    }

    @Singleton
    public static class StubTransactionManager extends AbstractTransactionOperations<String> {

        static final String RESOURCE = "stub-resource";

        int beginCount;
        int commitCount;
        int rollbackCount;
        int afterCompletionCount;
        RuntimeException beginFailure;
        RuntimeException commitFailure;
        RuntimeException rollbackFailure;

        void reset() {
            beginCount = 0;
            commitCount = 0;
            rollbackCount = 0;
            afterCompletionCount = 0;
            beginFailure = null;
            commitFailure = null;
            rollbackFailure = null;
        }

        @Override
        protected void doBegin(DefaultTransactionStatus<String> status) {
            beginCount++;
            if (beginFailure != null) {
                throw beginFailure;
            }
            bindResource(status, RESOURCE);
            status.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(Status status) {
                    afterCompletionCount++;
                }
            });
        }

        @Override
        protected void doCommit(DefaultTransactionStatus<String> status) {
            commitCount++;
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus<String> status) {
            rollbackCount++;
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }
    }

    @Singleton
    public static class LifecycleService {

        @Transactional
        public void succeeds() {
        }

        @Transactional
        public void marksRollbackOnly() {
            TransactionStatus<?> status = TransactionStatusHolder.get();
            status.setRollbackOnly();
        }

        @Transactional
        public void throwsRuntime() {
            throw new IllegalStateException("business");
        }
    }
}
