package io.github.fludakit.tx;

import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionContextTest {

    @Test
    void bindAndGetResourceByType() throws Exception {
        TransactionContextHolder.call(new TransactionContext(true), () -> {
            TransactionContext ctx = TransactionContextHolder.get();
            Object value = new Object();

            ctx.bindResource(Object.class, value);
            assertSame(value, ctx.getResource(Object.class));
            // a second binding of the same type replaces the first
            Object replacement = new Object();
            ctx.bindResource(Object.class, replacement);
            assertSame(replacement, ctx.getResource(Object.class));
            assertNull(ctx.getResource(String.class));
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
            ctx.registerCallback(new TransactionCallback() {
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
            ctx.triggerAfterCompletion(TransactionCallback.CompletionStatus.COMMITTED);

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

    @Test
    void bindResourceIfAbsentReturnsTheWinner() throws Exception {
        TransactionContext ctx = new TransactionContext(true);

        Object winner = new Object();
        assertNull(ctx.bindResourceIfAbsent(Object.class, winner));
        Object loser = new Object();
        assertSame(winner, ctx.bindResourceIfAbsent(Object.class, loser));
        assertSame(winner, ctx.getResource(Object.class));
    }

    @Test
    void mutationsAfterCompletionAreRejected() {
        TransactionContext ctx = new TransactionContext(true);
        ctx.markCompleted(TransactionCallback.CompletionStatus.COMMITTED);

        assertThrows(IllegalStateException.class, () -> ctx.bindResource(Object.class, new Object()));
        assertThrows(IllegalStateException.class, () -> ctx.registerCallback(new TransactionCallback() {
            @Override
            public void afterCompletion(CompletionStatus status) {
            }
        }));
        assertThrows(IllegalStateException.class, ctx::setRollbackOnly);
        assertThrows(IllegalStateException.class, () -> ctx.markCompleted(TransactionCallback.CompletionStatus.COMMITTED));
    }
}
