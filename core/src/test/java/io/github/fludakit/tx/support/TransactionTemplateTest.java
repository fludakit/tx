package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionManager;

import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the shared {@link TransactionTemplate} — the single owner of the propagation and
 * lifecycle state machine both front-ends delegate to.
 *
 * <p>These lock the contract the two front-ends rely on: propagation dispatch, the pluggable
 * {@link RollbackDecisionStrategy}, and the {@link CallbackRegistrar} hook.</p>
 */
class TransactionTemplateTest {

    private static final String RESOURCE = "resource";

    private StubTransactionManager txManager;
    private final List<TransactionContext> registered = new ArrayList<>();

    private TransactionTemplate<String> alwaysRollbackOnFailure;

    @BeforeEach
    void setUp() {
        txManager = new StubTransactionManager();
        registered.clear();
        alwaysRollbackOnFailure = new TransactionTemplate<>(txManager,
                RollbackDecisionStrategy.alwaysOnFailure(), registered::add);
    }

    private TransactionTemplate<String> classified() {
        return new TransactionTemplate<>(txManager, RollbackDecisionStrategy.classified(), registered::add);
    }

    // --- propagation ---

    @Test
    void required_beginsNewTransaction_bindsContextDuringExecution_releasesAfter() throws Exception {
        AtomicBoolean boundDuringExecution = new AtomicBoolean();

        String result = alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            assertSame(RESOURCE, resource);
            assertSame(txManager.lastContext, TransactionContextHolder.get());
            boundDuringExecution.set(true);
            return "done";
        });

        assertEquals("done", result);
        assertTrue(boundDuringExecution.get());
        assertNull(TransactionContextHolder.get(), "the binding must be released after completion");
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
        assertEquals(TransactionStatus.State.COMMITTED, txManager.lastContext.getStatus().getState());
    }

    @Test
    void required_joinsExisting_doesNotBeginOrCompleteOrRegister() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);
        registered.clear();

        TransactionContextHolder.call(outer, () -> {
            String result = alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                assertSame(outer.getStatus(), status);
                return "joined";
            });
            assertEquals("joined", result);
            return null;
        });

        assertEquals(1, txManager.beginCount, "the joined call must not begin a transaction");
        assertEquals(0, txManager.commitCount);
        assertTrue(registered.isEmpty(), "the registrar only fires for newly begun transactions");
    }

    @Test
    void requiresNew_beginsInsideExistingTransaction() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionContextHolder.call(outer, () -> {
            alwaysRollbackOnFailure.execute(def(Transactional.TxType.REQUIRES_NEW), (resource, status) -> "new");
            return null;
        });

        assertEquals(2, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void mandatory_withoutTransaction_throws() {
        TransactionException thrown = assertThrows(TransactionException.class, () ->
                alwaysRollbackOnFailure.execute(def(Transactional.TxType.MANDATORY), (resource, status) -> "never"));

        assertTrue(thrown.getMessage().contains("MANDATORY"));
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void never_withTransaction_throws() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionContextHolder.call(outer, () -> {
            assertThrows(TransactionException.class, () ->
                    alwaysRollbackOnFailure.execute(def(Transactional.TxType.NEVER), (resource, status) -> "never"));
            return null;
        });

        assertEquals(1, txManager.beginCount);
    }

    @Test
    void supports_withoutTransaction_runsNonTransactional() throws Exception {
        String result = alwaysRollbackOnFailure.execute(def(Transactional.TxType.SUPPORTS), (resource, status) -> {
            assertSame(TransactionContext.NON_TRANSACTIONAL.getStatus(), status);
            return "non-tx";
        });

        assertEquals("non-tx", result);
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void notSupported_suspendsExisting_andRestores() throws Exception {
        TransactionContext outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionContextHolder.call(outer, () -> {
            alwaysRollbackOnFailure.execute(def(Transactional.TxType.NOT_SUPPORTED), (resource, status) -> {
                assertSame(TransactionContext.NON_TRANSACTIONAL.getStatus(), status);
                TransactionContext bound = TransactionContextHolder.get();
                assertTrue(bound == null || bound == TransactionContext.NON_TRANSACTIONAL,
                        "the outer transaction must be suspended");
                return "suspended";
            });
            assertSame(outer, TransactionContextHolder.get(), "the outer transaction must be restored");
            return null;
        });
    }

    // --- rollback decision strategies ---

    @Test
    void alwaysOnFailure_rollsBackCheckedException() {
        assertThrows(CheckedFailure.class, () ->
                alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new CheckedFailure("checked");
                }));

        assertEquals(1, txManager.rollbackCount);
        assertEquals(0, txManager.commitCount);
    }

    @Test
    void classified_rollsBackRuntimeExceptionByDefault() {
        assertThrows(IllegalStateException.class, () ->
                classified().execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void classified_commitsCheckedExceptionByDefault() throws Exception {
        assertThrows(CheckedFailure.class, () ->
                classified().execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new CheckedFailure("checked");
                }));

        assertEquals(1, txManager.commitCount, "a checked exception with no rollback rule must commit");
        assertEquals(0, txManager.rollbackCount);
    }

    @Test
    void classified_honorsExplicitDontRollbackOn() throws Exception {
        TransactionDefinition definition = new TransactionDefinition(Transactional.TxType.REQUIRED,
                new Class[]{Exception.class}, new Class[]{IllegalStateException.class}, false);

        assertThrows(IllegalStateException.class, () ->
                classified().execute(definition, (resource, status) -> {
                    throw new IllegalStateException("dont roll back");
                }));

        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
    }

    @Test
    void classified_honorsExplicitRollbackOn() {
        TransactionDefinition definition = new TransactionDefinition(Transactional.TxType.REQUIRED,
                new Class[]{CheckedFailure.class}, new Class[0], false);

        assertThrows(CheckedFailure.class, () ->
                classified().execute(definition, (resource, status) -> {
                    throw new CheckedFailure("roll back");
                }));

        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void classified_unwrapsInvocationTargetExceptionBeforeClassifying() throws Exception {
        TransactionDefinition definition = new TransactionDefinition(Transactional.TxType.REQUIRED,
                new Class[0], new Class[]{IllegalStateException.class}, false);

        assertThrows(InvocationTargetException.class, () ->
                classified().execute(definition, (resource, status) -> {
                    throw new InvocationTargetException(new IllegalStateException("wrapped"));
                }));

        assertEquals(1, txManager.commitCount,
                "the dontRollbackOn rule must match the unwrapped target, not the wrapper");
    }

    @Test
    void rollbackOnly_forcesRollback_onNormalCompletion() throws Exception {
        alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            status.setRollbackOnly();
            return null;
        });

        assertEquals(1, txManager.rollbackCount);
        assertEquals(0, txManager.commitCount);
        assertEquals(TransactionStatus.State.ROLLED_BACK, txManager.lastContext.getStatus().getState());
    }

    @Test
    void commitFailure_marksContextCompletedUnknown_andPropagates() {
        txManager.failCommit = true;

        assertThrows(TransactionException.class, () ->
                alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> "done"));

        assertEquals(TransactionStatus.State.UNKNOWN, txManager.lastContext.getStatus().getState());
        assertNull(TransactionContextHolder.get(), "the binding must be released even when the commit fails");
    }

    @Test
    void completionIsNotRepeated_whenTheUnitOfWorkAlreadyCompletedTheContext() throws Exception {
        alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            // simulate a unit of work that completed the transaction itself
            TransactionContextHolder.get().markCompleted(TransactionCallback.CompletionStatus.COMMITTED);
            return "done";
        });

        assertEquals(0, txManager.commitCount, "the template must not commit an already-completed context");
        assertEquals(0, txManager.rollbackCount);
    }

    // --- callback registrar ---

    @Test
    void callbackRegistrar_runsOncePerNewlyBegunTransaction() throws Exception {
        alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            alwaysRollbackOnFailure.execute(def(Transactional.TxType.REQUIRES_NEW), (inner, innerStatus) -> "inner");
            return "outer";
        });

        assertEquals(2, registered.size());
        assertSame(txManager.firstContext, registered.get(0));
        assertSame(txManager.lastContext, registered.get(1));
    }

    @Test
    void callbacksFireInLifecycleOrder_onCommit() throws Exception {
        List<String> events = new ArrayList<>();
        txManager.callbackEvents = events;

        alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> "done");

        assertEquals(List.of("beforeCommit:false", "beforeCompletion", "afterCommit",
                "afterCompletion:COMMITTED"), events);
    }

    @Test
    void callbacksFireInLifecycleOrder_onRollback() {
        List<String> events = new ArrayList<>();
        txManager.callbackEvents = events;

        assertThrows(IllegalStateException.class, () ->
                alwaysRollbackOnFailure.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals(List.of("beforeCompletion", "afterCompletion:ROLLED_BACK"), events);
    }

    // --- helpers ---

    private static TransactionDefinition def(Transactional.TxType type) {
        return new TransactionDefinition(type, new Class[0], new Class[0], false);
    }

    private static final class CheckedFailure extends Exception {
        CheckedFailure(String message) {
            super(message);
        }
    }

    private class StubTransactionManager implements TransactionManager<String> {
        int beginCount;
        int commitCount;
        int rollbackCount;
        boolean failCommit;
        TransactionContext firstContext;
        TransactionContext lastContext;
        List<String> callbackEvents;

        @Override
        public TransactionContext getTransaction(TransactionDefinition definition) {
            beginCount++;
            TransactionContext context = new TransactionContext(true, TransactionStatus.initial(definition, null));
            if (callbackEvents != null) {
                context.registerCallback(new TransactionCallback() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        callbackEvents.add("beforeCommit:" + readOnly);
                    }

                    @Override
                    public void beforeCompletion() {
                        callbackEvents.add("beforeCompletion");
                    }

                    @Override
                    public void afterCommit() {
                        callbackEvents.add("afterCommit");
                    }

                    @Override
                    public void afterCompletion(CompletionStatus status) {
                        callbackEvents.add("afterCompletion:" + status);
                    }
                });
            }
            if (firstContext == null) {
                firstContext = context;
            }
            lastContext = context;
            return context;
        }

        @Override
        public void commit(TransactionContext context) {
            commitCount++;
            if (failCommit) {
                throw new TransactionException("commit failed");
            }
        }

        @Override
        public void rollback(TransactionContext context) {
            rollbackCount++;
        }

        @Override
        public String getResource() {
            return RESOURCE;
        }
    }
}
