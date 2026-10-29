package io.github.fludakit.tx;

import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionContextHolderTest {

    @Test
    void callBindsAndRestores() throws Exception {
        assertFalse(TransactionContextHolder.isBound());
        assertNull(TransactionContextHolder.get());

        TransactionContextHolder.call(new TransactionContext(true), () -> {
            assertTrue(TransactionContextHolder.isBound());
            assertNotNull(TransactionContextHolder.get());
            return null;
        });

        assertFalse(TransactionContextHolder.isBound());
        assertNull(TransactionContextHolder.get());
    }

    @Test
    void nestedCallShadowsAndRestores() throws Exception {
        TransactionContext outer = new TransactionContext(true);
        TransactionContext inner = new TransactionContext(true);

        TransactionContextHolder.call(outer, () -> {
            assertSame(outer, TransactionContextHolder.get());

            TransactionContextHolder.call(inner, () -> {
                assertSame(inner, TransactionContextHolder.get());
                return null;
            });

            assertSame(outer, TransactionContextHolder.get(), "outer must be restored after the inner scope");
            return null;
        });

        assertFalse(TransactionContextHolder.isBound());
    }

    @Test
    void nonTransactionalContextIsStillBound() throws Exception {
        TransactionContextHolder.call(TransactionContext.NON_TRANSACTIONAL, () -> {
            assertTrue(TransactionContextHolder.isBound());
            assertSame(TransactionContext.NON_TRANSACTIONAL, TransactionContextHolder.get());
            assertFalse(TransactionContextHolder.get().isActualTransactionActive());
            return null;
        });

        assertFalse(TransactionContextHolder.isBound());
    }

    @Test
    void completedContextIsNotReturnedAsCurrent() throws Exception {
        TransactionContext completed = new TransactionContext(true);
        completed.markCompleted(TransactionCallback.CompletionStatus.COMMITTED);
        assertTrue(completed.isCompleted());

        TransactionContextHolder.call(completed, () -> {
            // a completed context must never be handed back, even when bound via call
            assertNull(TransactionContextHolder.get());
            assertFalse(TransactionContextHolder.isBound());
            return null;
        });
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21, max = JRE.JAVA_21)
    void inheritedBindingIsRevokedWhenTheScopeCloses() throws Exception {
        CountDownLatch childDone = new CountDownLatch(1);
        AtomicBoolean childSawContext = new AtomicBoolean(false);
        AtomicReference<Thread> childRef = new AtomicReference<>();

        TransactionContextHolder.call(new TransactionContext(true), () -> {
            Thread child = new Thread(() -> {
                childSawContext.set(TransactionContextHolder.get() != null);
                childDone.countDown();
            });
            childRef.set(child);
            child.start();
            assertTrue(childDone.await(5, TimeUnit.SECONDS), "child thread should inherit the context");
            assertNotNull(TransactionContextHolder.get(), "the scope must still see its context");
            return null;
        });

        assertTrue(childDone.await(5, TimeUnit.SECONDS));
        childRef.get().join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(childSawContext.get(), "a thread created inside the scope inherits the context");
    }
}
