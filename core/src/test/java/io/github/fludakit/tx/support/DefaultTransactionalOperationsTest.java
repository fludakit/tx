package io.github.fludakit.tx.support;

import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTransactionalOperationsTest {

    private StubTransactionManager txManager;
    private DefaultTransactionalOperations txOps;

    @BeforeEach
    void setUp() {
        txManager = new StubTransactionManager();
        txOps = new DefaultTransactionalOperations(txManager);
    }

    @Test
    void execute_required_createsNewTransaction() throws Exception {
        String result = txOps.execute(ctx -> {
            assertTrue(ctx.isActualTransactionActive());
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
            String result = txOps.execute(ctx -> {
                assertSame(outer, ctx);
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

            String result = txOps.execute(def, ctx -> {
                assertNotSame(outer, ctx);
                assertTrue(ctx.isActualTransactionActive());
                return "new";
            });

            assertEquals("new", result);
            assertEquals(2, txManager.beginCount);
            return null;
        });
        cleanup(outer);
    }

    @Test
    void execute_runtimeException_triggersRollback() {
        assertThrows(IllegalStateException.class, () ->
                txOps.execute(ctx -> {
                    throw new IllegalStateException("boom");
                })
        );

        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void execute_rollbackOnly_forcesRollback() throws Exception {
        txOps.execute(ctx -> {
            ctx.setRollbackOnly();
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
                txOps.execute(def, ctx -> "should not reach")
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
                    txOps.execute(def, ctx -> "should not reach")
            );
            return null;
        });
        cleanup(outer);
    }

    @Test
    void executeRead_usesReadOnlyDefinition() throws Exception {
        txOps.executeRead(ctx -> {
            assertTrue(ctx.isActualTransactionActive());
            return null;
        });

        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void executeWrite_usesDefaultDefinition() throws Exception {
        txOps.executeWrite(ctx -> {
            assertTrue(ctx.isActualTransactionActive());
            return null;
        });

        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
    }

    private void cleanup(TransactionContext context) {
        if (!context.isCompleted()) {
            context.getCallbacks().forEach(
                    s -> s.afterCompletion(TransactionSynchronization.CompletionStatus.ROLLED_BACK));
        }
    }

    private static class StubTransactionManager implements PlatformTransactionManager {
        int beginCount;
        int commitCount;
        int rollbackCount;

        @Override
        public TransactionContext getTransaction(TransactionDefinition definition) {
            beginCount++;
            TransactionContext context = new TransactionContext(true);
            context.getCallbacks().add(new TransactionSynchronization() {});
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
    }
}
