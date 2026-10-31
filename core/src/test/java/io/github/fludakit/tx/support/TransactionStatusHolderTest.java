package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link TransactionStatusHolder}: the scope binding that makes a transaction
 * ambient, and the guarantee that it never outlives its boundary.
 */
class TransactionStatusHolderTest {

    @Test
    void callBindsAndRestores() throws Exception {
        DefaultTransactionStatus<String> status = transaction();

        assertNull(TransactionStatusHolder.get());
        assertFalse(TransactionStatusHolder.isBound());

        TransactionStatusHolder.call(status, () -> {
            assertTrue(TransactionStatusHolder.isBound());
            assertSame(status, TransactionStatusHolder.get());
            return null;
        });

        assertNull(TransactionStatusHolder.get());
        assertFalse(TransactionStatusHolder.isBound());
    }

    @Test
    void nestedCallShadowsAndRestores() throws Exception {
        DefaultTransactionStatus<String> outer = transaction();
        DefaultTransactionStatus<String> inner = transaction();

        TransactionStatusHolder.call(outer, () -> {
            assertSame(outer, TransactionStatusHolder.get());

            TransactionStatusHolder.call(inner, () -> {
                assertSame(inner, TransactionStatusHolder.get());
                return null;
            });

            assertSame(outer, TransactionStatusHolder.get(), "the outer status must be restored");
            return null;
        });

        assertFalse(TransactionStatusHolder.isBound());
    }

    @Test
    void noTransactionStatusIsStillBound() throws Exception {
        DefaultTransactionStatus<String> noTransaction = DefaultTransactionStatus.noTransaction();

        TransactionStatusHolder.call(noTransaction, () -> {
            assertTrue(TransactionStatusHolder.isBound());
            assertSame(noTransaction, TransactionStatusHolder.get());
            assertFalse(TransactionStatusHolder.get().isActualTransactionActive());
            return null;
        });
    }

    @Test
    void completedStatusStaysVisibleForCompletionCallbacks() throws Exception {
        DefaultTransactionStatus<String> status = transaction();
        status.markCompleted(TransactionSynchronization.Status.COMMITTED);

        TransactionStatusHolder.call(status, () -> {
            assertSame(status, TransactionStatusHolder.get(),
                    "afterCommit/afterCompletion still need to reach the transaction's state");
            assertTrue(status.isCompleted());
            return null;
        });
    }

    @Test
    void callRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> TransactionStatusHolder.call(null, () -> null));
        assertThrows(NullPointerException.class, () -> TransactionStatusHolder.call(transaction(), null));
    }

    @Test
    void bindingOfAThreadCreatedInsideTheScopeIsRevokedWhenItCloses() throws Exception {
        assumeTrue(Runtime.version().feature() == 21,
                "the JDK 21 base implementation carries the status through an InheritableThreadLocal");

        CountDownLatch childReadWhileOpen = new CountDownLatch(1);
        CountDownLatch childMayReadAfterClose = new CountDownLatch(1);
        AtomicReference<TransactionStatus<?>> whileOpen = new AtomicReference<>();
        AtomicReference<TransactionStatus<?>> afterClose = new AtomicReference<>();
        AtomicReference<Thread> childReference = new AtomicReference<>();
        DefaultTransactionStatus<String> status = transaction();

        TransactionStatusHolder.call(status, () -> {
            // an InheritableThreadLocal is captured when the thread is constructed, not when it
            // starts, so the thread has to be created inside the scope
            Thread child = new Thread(() -> {
                whileOpen.set(TransactionStatusHolder.get());
                childReadWhileOpen.countDown();
                await(childMayReadAfterClose);
                afterClose.set(TransactionStatusHolder.get());
            }, "inheriting-worker");
            childReference.set(child);
            child.start();
            assertTrue(childReadWhileOpen.await(5, TimeUnit.SECONDS));
            return null;
        });

        childMayReadAfterClose.countDown();
        childReference.get().join(TimeUnit.SECONDS.toMillis(5));

        assertSame(status, whileOpen.get(), "a thread created inside the scope inherits the status");
        assertNull(afterClose.get(), "the inherited binding must be revoked when the scope closes");
    }

    // --- helpers -------------------------------------------------------------

    private static DefaultTransactionStatus<String> transaction() {
        return DefaultTransactionStatus.newTransaction(TransactionDefinition.DEFAULT, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
