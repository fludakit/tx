package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        String result = txOps.execute(res -> {
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
            String result = txOps.execute(res -> {
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

            String result = txOps.execute(def, res -> "new");

            assertEquals("new", result);
            assertEquals(2, txManager.beginCount);
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_runtimeException_triggersRollback() {
        assertThrows(IllegalStateException.class, () ->
                txOps.execute(res -> {
                    throw new IllegalStateException("boom");
                })
        );

        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void execute_rollbackOnly_forcesRollback() throws Exception {
        TransactionContextHolder.call(txManager.getTransaction(TransactionDefinition.DEFAULT), () -> {
            txOps.execute(res -> {
                TransactionContextHolder.get().setRollbackOnly();
                return null;
            });
            return null;
        });

        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void execute_mandatory_throwsWithoutTransaction() {
        TransactionDefinition def = new TransactionDefinition(
                jakarta.transaction.Transactional.TxType.MANDATORY,
                new Class[0], new Class[0], false);

        assertThrows(TransactionException.class, () ->
                txOps.execute(def, res -> "should not reach")
        );

        assertEquals(0, txManager.beginCount);
    }

    @Test
    void execute_never_throwsWithTransaction() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        TransactionContextHolder.call(outer, () -> {
            TransactionDefinition def = new TransactionDefinition(
                    jakarta.transaction.Transactional.TxType.NEVER,
                    new Class[0], new Class[0], false);

            assertThrows(TransactionException.class, () ->
                    txOps.execute(def, res -> "should not reach")
            );
            return null;
        });
        cleanup(outer);
    }

    @Test
    void executeRead_usesReadOnlyDefinition() throws Exception {
        txOps.executeRead(res -> {
            assertSame(RESOURCE, res);
            return null;
        });

        assertEquals(Boolean.TRUE, txManager.lastReadOnlyFlag);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void executeWrite_usesDefaultDefinition() throws Exception {
        txOps.executeWrite(res -> {
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

    private static class StubTransactionManager implements TransactionManager<String> {
        int beginCount;
        int commitCount;
        int rollbackCount;
        Boolean lastReadOnlyFlag;

        @Override
        public TransactionContext getTransaction(TransactionDefinition definition) {
            beginCount++;
            lastReadOnlyFlag = definition.readOnly();
            TransactionContext context = new TransactionContext(true);
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
        public String getResource(TransactionContext context) {
            return RESOURCE;
        }
    }
}
