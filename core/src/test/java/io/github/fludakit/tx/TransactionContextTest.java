package io.github.fludakit.tx;

import io.github.fludakit.tx.support.TransactionSynchronization;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionContextTest {

    @Test
    void bindGetUnbindResource() throws Exception {
        TransactionContextHolder.call(new TransactionContext(true), () -> {
            TransactionContext ctx = TransactionContextHolder.get();
            Object key = new Object();
            Object value = new Object();

            ctx.bindResource(key, value);
            assertSame(value, ctx.getResource(key));
            assertSame(value, ctx.unbindResource(key));
            assertNull(ctx.getResource(key));
            return null;
        });
    }

    @Test
    void isActualTransactionActiveReflectsState() throws Exception {
        TransactionContext noCtx = TransactionContextHolder.get();
        assertTrue(noCtx == null || !noCtx.isActualTransactionActive());

        TransactionContextHolder.call(new TransactionContext(true), () -> {
            assertTrue(TransactionContextHolder.get().isActualTransactionActive());
            return null;
        });

        TransactionContextHolder.call(TransactionContext.NON_TRANSACTIONAL, () -> {
            assertFalse(TransactionContextHolder.get().isActualTransactionActive());
            return null;
        });

        TransactionContext afterCtx = TransactionContextHolder.get();
        assertTrue(afterCtx == null || !afterCtx.isActualTransactionActive());
    }

    @Test
    void registerAndTriggerInOrder() throws Exception {
        TransactionContextHolder.call(new TransactionContext(true), () -> {
            TransactionContext ctx = TransactionContextHolder.get();
            List<String> calls = new ArrayList<>();
            ctx.registerCallback(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    calls.add("beforeCommit");
                }

                @Override
                public void beforeCompletion() {
                    calls.add("beforeCompletion");
                }

                @Override
                public void afterCommit() {
                    calls.add("afterCommit");
                }

                @Override
                public void afterCompletion(CompletionStatus status) {
                    calls.add("afterCompletion:" + status);
                }
            });

            ctx.triggerBeforeCommit(false);
            ctx.triggerBeforeCompletion();
            ctx.triggerAfterCommit();
            ctx.triggerAfterCompletion(TransactionSynchronization.CompletionStatus.COMMITTED);

            assertEquals(List.of("beforeCommit", "beforeCompletion", "afterCommit", "afterCompletion:COMMITTED"), calls);
            return null;
        });
    }

    @Test
    void rollbackOnlyFlag() throws Exception {
        TransactionContextHolder.call(new TransactionContext(true), () -> {
            TransactionContext ctx = TransactionContextHolder.get();
            assertFalse(ctx.isRollbackOnly());
            ctx.setRollbackOnly();
            assertTrue(ctx.isRollbackOnly());
            return null;
        });
    }
}
