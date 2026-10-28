package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.support.TransactionSavepointManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultTransactionalOperationsTest {

    private static final String RESOURCE = "resource";

    private StubTransactionManager txManager;
    private DefaultTransactionalOperations<String> txOps;

    @BeforeEach
    void setUp() {
        txManager = new StubTransactionManager();
        txOps = new DefaultTransactionalOperations<>(txManager);
    }

    @Test
    void execute_required_createsNewTransaction() throws Exception {
        String result = txOps.execute((res, status) -> {
            assertSame(RESOURCE, res);
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
    }

    @Test
    void execute_required_joinsExisting() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        TransactionContextHolder.call(outer, () -> {
            String result = txOps.execute((res, status) -> {
                assertSame(RESOURCE, res);
                return "joined";
            });
            assertEquals("joined", result);
            assertEquals(1, txManager.beginCount);
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_requiresNew_suspendsExisting() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        TransactionContextHolder.call(outer, () -> {
            TransactionDefinition def = new TransactionDefinition(
                    jakarta.transaction.Transactional.TxType.REQUIRES_NEW,
                    new Class[0], new Class[0], false);

            String result = txOps.execute(def, (res, status) -> "new");

            assertEquals("new", result);
            assertEquals(2, txManager.beginCount);
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_runtimeException_triggersRollback() {
        assertThrows(IllegalStateException.class, () ->
                txOps.execute((res, status) -> {
                    throw new IllegalStateException("boom");
                })
        );

        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void execute_rollbackOnly_forcesRollback() throws Exception {
        // A participating (REQUIRED) call that marks the shared transaction rollback-only must
        // force the outermost boundary to roll back even though it completes normally.
        txOps.execute((res, status) -> {
            txOps.execute((innerResource, innerStatus) -> {
                innerStatus.setRollbackOnly();
                return null;
            });
            return null;
        });

        assertEquals(1, txManager.beginCount, "the inner REQUIRED call must join, not begin");
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void execute_mandatory_throwsWithoutTransaction() {
        TransactionDefinition def = new TransactionDefinition(
                jakarta.transaction.Transactional.TxType.MANDATORY,
                new Class[0], new Class[0], false);

        // TransactionException extends the spec's TransactionalException, so the programmatic
        // path satisfies the same contract the @Transactional interceptor must follow
        Throwable thrown = assertThrows(TransactionException.class, () ->
                txOps.execute(def, (res, status) -> "should not reach")
        );
        assertInstanceOf(jakarta.transaction.TransactionalException.class, thrown);

        assertEquals(0, txManager.beginCount);
    }

    @Test
    void execute_error_propagatesRawAndTriggersRollback() {
        // Errors are never wrapped — the callback's Error surfaces itself
        assertThrows(SampleError.class, () ->
                txOps.execute((res, status) -> {
                    throw new SampleError("fatal");
                })
        );

        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    private static final class SampleError extends Error {
        SampleError(String message) {
            super(message);
        }
    }

    @Test
    void execute_never_throwsWithTransaction() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        TransactionContextHolder.call(outer, () -> {
            TransactionDefinition def = new TransactionDefinition(
                    jakarta.transaction.Transactional.TxType.NEVER,
                    new Class[0], new Class[0], false);

            assertThrows(TransactionException.class, () ->
                    txOps.execute(def, (res, status) -> "should not reach")
            );
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_supports_joinsExistingWithoutBeginning() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        TransactionContextHolder.call(outer, () -> {
            TransactionDefinition def = new TransactionDefinition(
                    jakarta.transaction.Transactional.TxType.SUPPORTS,
                    new Class[0], new Class[0], false);

            String result = txOps.execute(def, (res, status) -> {
                assertSame(RESOURCE, res);
                assertSame(outer.getStatus(), status, "SUPPORTS must run in the existing transaction's status");
                return "joined";
            });

            assertEquals("joined", result);
            assertEquals(1, txManager.beginCount, "no new transaction may be begun");
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_supports_withoutActiveTransaction_runsNonTransactionally() throws Exception {
        TransactionDefinition def = new TransactionDefinition(
                jakarta.transaction.Transactional.TxType.SUPPORTS,
                new Class[0], new Class[0], false);

        String result = txOps.execute(def, (res, status) -> {
            assertSame(TransactionContext.NON_TRANSACTIONAL.getStatus(), status);
            return "non-tx";
        });

        assertEquals("non-tx", result);
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void execute_notSupported_suspendsExisting() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        TransactionContextHolder.call(outer, () -> {
            TransactionDefinition def = new TransactionDefinition(
                    jakarta.transaction.Transactional.TxType.NOT_SUPPORTED,
                    new Class[0], new Class[0], false);

            String result = txOps.execute(def, (res, status) -> {
                assertNotSame(outer.getStatus(), status, "the outer transaction must be suspended");
                assertSame(TransactionContext.NON_TRANSACTIONAL.getStatus(), status);
                return "suspended";
            });

            assertEquals("suspended", result);
            assertEquals(1, txManager.beginCount, "no new transaction may be begun");
            assertSame(outer, TransactionContextHolder.get(), "the outer transaction must be restored");
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_notSupported_withoutActiveTransaction_runsNonTransactionally() throws Exception {
        TransactionDefinition def = new TransactionDefinition(
                jakarta.transaction.Transactional.TxType.NOT_SUPPORTED,
                new Class[0], new Class[0], false);

        String result = txOps.execute(def, (res, status) -> {
            assertSame(TransactionContext.NON_TRANSACTIONAL.getStatus(), status);
            return "non-tx";
        });

        assertEquals("non-tx", result);
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void executeRead_usesReadOnlyDefinition() throws Exception {
        txOps.executeRead((res, status) -> {
            assertSame(RESOURCE, res);
            return null;
        });

        assertEquals(Boolean.TRUE, txManager.lastReadOnlyFlag);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void executeWrite_usesDefaultDefinition() throws Exception {
        txOps.executeWrite((res, status) -> {
            assertSame(RESOURCE, res);
            return null;
        });

        assertEquals(Boolean.FALSE, txManager.lastReadOnlyFlag);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
    }

    private void cleanup(TransactionContext context) {
        if (!context.isCompleted()) {
            context.getCallbacks().forEach(
                    s -> s.afterCompletion(TransactionCallback.CompletionStatus.ROLLED_BACK));
        }
    }

    @Test
    void executeNested_success_releasesAndOuterCommits() throws Exception {
        txOps.execute((res, status) -> {
            String nested = txOps.executeNested((inner, nestedStatus) -> {
                assertSame(RESOURCE, inner);
                assertTrue(nestedStatus.isSavepointSupported());
                return "inner";
            });
            assertEquals("inner", nested);
            return null;
        });

        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
        assertEquals(1, txManager.savepointCount);
        assertEquals(1, txManager.releaseCount);
        assertEquals(0, txManager.rollbackToCount);
    }

    @Test
    void executeNested_failure_scopesRollbackToSavepoint_outerContinues() throws Exception {
        String result = txOps.execute((res, status) -> {
            assertThrows(IllegalStateException.class, () ->
                    txOps.executeNested((inner, nestedStatus) -> {
                        throw new IllegalStateException("nested failure");
                    }));
            // the surrounding transaction continues after the scoped rollback
            return "outer-survived";
        });

        assertEquals("outer-survived", result);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
        assertEquals(1, txManager.savepointCount);
        assertEquals(1, txManager.rollbackToCount);
        assertEquals(0, txManager.releaseCount);
    }

    @Test
    void executeNested_withoutActiveTransaction_beginsFullTransaction() throws Exception {
        String result = txOps.executeNested((res, status) -> "standalone");

        assertEquals("standalone", result);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.savepointCount);
    }

    private class StubTransactionManager implements TransactionManager<String>, TransactionSavepointManager {
        int beginCount;
        int commitCount;
        int rollbackCount;
        int savepointCount;
        int releaseCount;
        int rollbackToCount;
        Boolean lastReadOnlyFlag;

        @Override
        public TransactionContext getTransaction(TransactionDefinition definition) {
            beginCount++;
            lastReadOnlyFlag = definition.readOnly();
            TransactionContext context = new TransactionContext(true,
                    TransactionStatus.initial(definition, this));
            context.registerCallback(new TransactionCallback() {});
            return context;
        }

        @Override
        public void commit(TransactionContext context) {
            commitCount++;
        }

        @Override
        public void rollback(TransactionContext context) {
            rollbackCount++;
        }

        @Override
        public String getResource() {
            return RESOURCE;
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            savepointCount++;
            return () -> "sp" + savepointCount;
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            rollbackToCount++;
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            releaseCount++;
        }
    }
}
