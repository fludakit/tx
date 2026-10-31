package io.github.fludakit.tx.support;

import io.github.fludakit.tx.IllegalTransactionStateException;
import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.Propagation;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.TransactionUsageException;
import io.github.fludakit.tx.UnexpectedRollbackException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AbstractTransactionOperations} — the one owner of the propagation table,
 * the lifecycle state machine, and the rollback rules.
 *
 * <p>Both front-ends delegate here, so these tests lock the contract they rely on rather than the
 * behaviour of either front-end.</p>
 */
class AbstractTransactionOperationsTest {

    private StubTransactionManager txManager;

    @BeforeEach
    void setUp() {
        txManager = new StubTransactionManager();
    }

    // --- REQUIRED ------------------------------------------------------------

    @Test
    void required_beginsCommitsAndBindsTheStatus() throws Exception {
        AtomicReference<TransactionStatus<String>> seen = new AtomicReference<>();

        String result = txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            assertSame(StubTransactionManager.RESOURCE, resource);
            assertSame(status, TransactionStatusHolder.get());
            assertTrue(status.isNewTransaction());
            assertFalse(status.isNestedTransaction());
            assertFalse(status.isCompleted());
            seen.set(status);
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
        assertTrue(seen.get().isCompleted());
        assertEquals(TransactionSynchronization.Status.COMMITTED, completionStatus(seen.get()));
        assertNull(TransactionStatusHolder.get(), "the binding must be gone once the boundary closes");
    }

    @Test
    void required_joinsTheRunningTransaction() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            String result = txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                assertSame(StubTransactionManager.RESOURCE, resource);
                assertFalse(status.isNewTransaction());
                assertFalse(status.isCompleted());
                return "joined";
            });

            assertEquals("joined", result);
            assertEquals(1, txManager.beginCount, "a participant must not begin a transaction");
            assertEquals(0, txManager.commitCount, "a participant must not commit");
            assertEquals(0, txManager.rollbackCount);
            assertSame(outer, TransactionStatusHolder.get());
            return null;
        });

        txManager.commit(outer);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void required_participantCanMarkTheTransactionRollbackOnly() {
        UnexpectedRollbackException thrown = assertThrows(UnexpectedRollbackException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    txManager.execute(TransactionDefinition.DEFAULT, (innerResource, inner) -> {
                        inner.setRollbackOnly();
                        return null;
                    });
                    return "outer returns normally";
                }));

        assertTrue(thrown.getMessage().contains("rollback-only"));
        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void required_boundaryRollbackOnlyRollsBackSilently() throws Exception {
        String result = txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            status.setRollbackOnly();
            return "quiet";
        });

        assertEquals("quiet", result);
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    // --- MANDATORY / NEVER / SUPPORTS / NOT_SUPPORTED ------------------------

    @Test
    void mandatory_withoutTransaction_throws() {
        IllegalTransactionStateException thrown = assertThrows(IllegalTransactionStateException.class, () ->
                txManager.execute(definition(Propagation.MANDATORY), (resource, status) -> "never"));

        assertTrue(thrown.getMessage().contains("mandatory"));
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void mandatory_joinsTheRunningTransaction() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            String result = txManager.execute(definition(Propagation.MANDATORY), (resource, status) -> {
                assertSame(StubTransactionManager.RESOURCE, resource);
                assertFalse(status.isNewTransaction());
                return "joined";
            });
            assertEquals("joined", result);
            assertEquals(0, txManager.commitCount);
            return null;
        });

        txManager.commit(outer);
    }

    @Test
    void never_withTransaction_throws() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            TransactionUsageException thrown = assertThrows(TransactionUsageException.class, () ->
                    txManager.execute(definition(Propagation.NEVER), (resource, status) -> "never"));
            assertTrue(thrown.getMessage().contains("never"));
            return null;
        });

        txManager.commit(outer);
    }

    @Test
    void never_withoutTransaction_runsWithoutOne() throws Exception {
        String result = txManager.execute(definition(Propagation.NEVER), (resource, status) -> {
            assertNull(resource);
            assertFalse(status.isActualTransactionActive());
            assertTrue(TransactionStatusHolder.isBound(), "the no-transaction status is still bound");
            return "non-tx";
        });

        assertEquals("non-tx", result);
        assertEquals(0, txManager.beginCount);
        assertNull(TransactionStatusHolder.get());
    }

    @Test
    void supports_withoutTransaction_runsWithoutOne() throws Exception {
        String result = txManager.execute(definition(Propagation.SUPPORTS), (resource, status) -> {
            assertNull(resource);
            assertFalse(status.isActualTransactionActive());
            return "non-tx";
        });

        assertEquals("non-tx", result);
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void supports_withTransaction_joinsIt() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            String result = txManager.execute(definition(Propagation.SUPPORTS), (resource, status) -> {
                assertSame(StubTransactionManager.RESOURCE, resource);
                assertTrue(status.isActualTransactionActive());
                assertFalse(status.isNewTransaction());
                return "joined";
            });
            assertEquals("joined", result);
            assertEquals(1, txManager.beginCount);
            return null;
        });

        txManager.commit(outer);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void notSupported_suspendsTheRunningTransactionAndRestoresIt() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            String result = txManager.execute(definition(Propagation.NOT_SUPPORTED), (resource, status) -> {
                assertNull(resource);
                assertFalse(status.isActualTransactionActive());
                return "suspended";
            });

            assertEquals("suspended", result);
            assertEquals(1, txManager.suspendCount);
            assertEquals(1, txManager.resumeCount);
            assertSame(outer, TransactionStatusHolder.get(), "the outer transaction must be restored");
            return null;
        });

        txManager.commit(outer);
    }

    @Test
    void notSupported_withoutTransaction_doesNotSuspend() throws Exception {
        txManager.execute(definition(Propagation.NOT_SUPPORTED), (resource, status) -> "non-tx");

        assertEquals(0, txManager.suspendCount);
        assertEquals(0, txManager.resumeCount);
        assertEquals(0, txManager.beginCount);
    }

    @Test
    void requiresNew_suspendsTheRunningTransactionAndStartsAnother() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            String result = txManager.execute(definition(Propagation.REQUIRES_NEW), (resource, status) -> {
                assertSame(StubTransactionManager.RESOURCE, resource);
                assertTrue(status.isNewTransaction());
                return "inner";
            });

            assertEquals("inner", result);
            assertEquals(2, txManager.beginCount);
            assertEquals(1, txManager.commitCount, "the inner transaction commits on its own boundary");
            assertEquals(1, txManager.suspendCount);
            assertEquals(1, txManager.resumeCount);
            assertSame(outer, TransactionStatusHolder.get());
            return null;
        });

        txManager.commit(outer);
        assertEquals(2, txManager.commitCount);
    }

    @Test
    void requiresNew_withoutTransaction_startsOneWithoutSuspending() throws Exception {
        txManager.execute(definition(Propagation.REQUIRES_NEW), (resource, status) -> "new");

        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.suspendCount);
        assertEquals(1, txManager.commitCount);
    }

    // --- rollback rules ------------------------------------------------------

    @Test
    void runtimeFailure_rollsBackAndPropagates() {
        assertThrows(IllegalStateException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void error_rollsBackAndPropagatesRaw() {
        assertThrows(SampleError.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new SampleError("fatal");
                }));

        assertEquals(1, txManager.rollbackCount);
    }

    @Test
    void checkedFailure_commitsByDefault() {
        assertThrows(CheckedFailure.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new CheckedFailure("checked");
                }));

        assertEquals(1, txManager.commitCount, "a checked failure with no rule must commit");
        assertEquals(0, txManager.rollbackCount);
    }

    @Test
    void checkedFailure_withExplicitRollbackRule_rollsBack() {
        TransactionDefinition definition = new TransactionDefinition(Propagation.REQUIRED,
                new Class[]{CheckedFailure.class}, new Class[0], false);

        assertThrows(CheckedFailure.class, () ->
                txManager.execute(definition, (resource, status) -> {
                    throw new CheckedFailure("checked");
                }));

        assertEquals(1, txManager.rollbackCount);
        assertEquals(0, txManager.commitCount);
    }

    @Test
    void dontRollbackOn_winsOverRollbackOn() throws Exception {
        TransactionDefinition definition = new TransactionDefinition(Propagation.REQUIRED,
                new Class[]{Exception.class}, new Class[]{IllegalStateException.class}, false);

        assertThrows(IllegalStateException.class, () ->
                txManager.execute(definition, (resource, status) -> {
                    throw new IllegalStateException("do not roll back");
                }));

        assertEquals(1, txManager.commitCount);
    }

    @Test
    void rulesMatchTheUnwrappedInvocationTargetException() throws Exception {
        TransactionDefinition definition = new TransactionDefinition(Propagation.REQUIRED,
                new Class[0], new Class[]{IllegalStateException.class}, false);

        assertThrows(InvocationTargetException.class, () ->
                txManager.execute(definition, (resource, status) -> {
                    throw new InvocationTargetException(new IllegalStateException("wrapped"));
                }));

        assertEquals(1, txManager.commitCount, "the rule must match the target, not the wrapper");
    }

    @Test
    void rulesMatchTheUnwrappedExecutionException() throws Exception {
        TransactionDefinition definition = new TransactionDefinition(Propagation.REQUIRED,
                new Class[]{CheckedFailure.class}, new Class[0], false);

        assertThrows(ExecutionException.class, () ->
                txManager.execute(definition, (resource, status) -> {
                    throw new ExecutionException(new CheckedFailure("wrapped"));
                }));

        assertEquals(1, txManager.rollbackCount);
    }

    // --- completion failures ------------------------------------------------

    @Test
    void commitFailure_rollsBackBestEffortAndPropagates() {
        txManager.failCommit = true;
        AtomicReference<TransactionStatus<String>> seen = new AtomicReference<>();

        TransactionException thrown = assertThrows(TransactionException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    seen.set(status);
                    return "done";
                }));

        assertEquals(StubTransactionManager.COMMIT_FAILURE, thrown.getMessage());
        assertEquals(1, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount, "a failed commit must be rolled back best-effort");
        assertEquals(TransactionSynchronization.Status.ROLLED_BACK, completionStatus(seen.get()));
        assertTrue(seen.get().isCompleted());
    }

    @Test
    void commitAndRollbackFailure_leavesTheStatusUnknown() {
        txManager.failCommit = true;
        txManager.failRollback = true;
        AtomicReference<TransactionStatus<String>> seen = new AtomicReference<>();

        TransactionException thrown = assertThrows(TransactionException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    seen.set(status);
                    return "done";
                }));

        assertEquals(TransactionSynchronization.Status.UNKNOWN, completionStatus(seen.get()));
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(StubTransactionManager.ROLLBACK_FAILURE, thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void applicationFailureStaysPrimaryWhenCompletionAlsoFails() {
        txManager.failCommit = true;

        CheckedFailure thrown = assertThrows(CheckedFailure.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    throw new CheckedFailure("application failure");
                }));

        assertEquals("application failure", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(StubTransactionManager.COMMIT_FAILURE, thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void beginFailure_completesTheStatusUnknownAndDoesNotStayBound() {
        txManager.failBegin = true;

        TransactionException thrown = assertThrows(TransactionException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> "never"));

        assertEquals(StubTransactionManager.BEGIN_FAILURE, thrown.getMessage());
        assertTrue(txManager.lastBegun.isCompleted());
        assertEquals(TransactionSynchronization.Status.UNKNOWN, txManager.lastBegun.getCompletionStatus());
        assertNull(TransactionStatusHolder.get());
    }

    // --- synchronizations ----------------------------------------------------

    @Test
    void synchronizations_fireInLifecycleOrderOnCommit() throws Exception {
        List<String> events = new ArrayList<>();

        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            status.registerSynchronization(recorder(events));
            return null;
        });

        assertEquals(List.of("beforeCommit:false", "beforeCompletion", "afterCommit",
                "afterCompletion:COMMITTED"), events);
    }

    @Test
    void synchronizations_fireInLifecycleOrderOnRollback() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    status.registerSynchronization(recorder(events));
                    throw new IllegalStateException("boom");
                }));

        assertEquals(List.of("beforeCompletion", "afterCompletion:ROLLED_BACK"), events);
    }

    @Test
    void beforeCommit_receivesTheReadOnlyFlag() throws Exception {
        List<String> events = new ArrayList<>();

        txManager.executeRead((resource, status) -> {
            status.registerSynchronization(recorder(events));
            return null;
        });

        assertTrue(events.contains("beforeCommit:true"));
    }

    @Test
    void beforeCommitFailure_abortsTheCommitAndRollsBack() {
        List<String> events = new ArrayList<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    status.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            events.add("beforeCommit");
                            throw new IllegalStateException("veto");
                        }

                        @Override
                        public void beforeCompletion() {
                            events.add("beforeCompletion");
                        }

                        @Override
                        public void afterCommit() {
                            events.add("afterCommit");
                        }

                        @Override
                        public void afterCompletion(TransactionSynchronization.Status status) {
                            events.add("afterCompletion:" + status);
                        }
                    });
                    return "never";
                }));

        assertEquals("veto", thrown.getMessage());
        assertEquals(0, txManager.commitCount);
        assertEquals(1, txManager.rollbackCount);
        assertEquals(List.of("beforeCommit", "beforeCompletion", "afterCompletion:ROLLED_BACK"), events);
    }

    @Test
    void afterCommitFailure_doesNotUndoTheCommit() {
        List<String> events = new ArrayList<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                    status.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            events.add("beforeCommit");
                        }

                        @Override
                        public void beforeCompletion() {
                            events.add("beforeCompletion");
                        }

                        @Override
                        public void afterCommit() {
                            events.add("afterCommit");
                            throw new IllegalStateException("after commit failure");
                        }

                        @Override
                        public void afterCompletion(TransactionSynchronization.Status status) {
                            events.add("afterCompletion:" + status);
                        }
                    });
                    return "done";
                }));

        assertEquals("after commit failure", thrown.getMessage());
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount, "the commit already happened and must stand");
        assertEquals(List.of("beforeCommit", "beforeCompletion", "afterCommit",
                "afterCompletion:COMMITTED"), events);
    }

    @Test
    void participantRegisteredSynchronizationFiresOnceAtTheRealBoundary() throws Exception {
        List<String> events = new ArrayList<>();

        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            txManager.execute(TransactionDefinition.DEFAULT, (innerResource, inner) -> {
                inner.registerSynchronization(recorder(events));
                return null;
            });
            return null;
        });

        assertEquals(List.of("beforeCommit:false", "beforeCompletion", "afterCommit",
                "afterCompletion:COMMITTED"), events);
    }

    @Test
    void completedStatus_rejectsNewSynchronizations() throws Exception {
        AtomicReference<TransactionStatus<String>> seen = new AtomicReference<>();

        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            seen.set(status);
            return null;
        });

        assertThrows(IllegalTransactionStateException.class, () ->
                seen.get().registerSynchronization(new TransactionSynchronization() {
                }));
    }

    // --- NESTED --------------------------------------------------------------

    @Test
    void nested_successReleasesTheSavepointAndTheOuterCommits() throws Exception {
        AtomicReference<TransactionStatus<String>> outer = new AtomicReference<>();

        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            outer.set(status);
            String result = txManager.executeNested((innerResource, nested) -> {
                assertSame(StubTransactionManager.RESOURCE, innerResource);
                assertTrue(nested.isNestedTransaction());
                assertFalse(nested.isNewTransaction());
                assertTrue(nested.isSavepointSupported());
                return "inner";
            });
            assertEquals("inner", result);
            return null;
        });

        assertEquals(1, txManager.beginCount);
        assertEquals(1, txManager.savepointCount);
        assertEquals(1, txManager.releaseCount);
        assertEquals(0, txManager.rollbackToCount);
        assertEquals(1, txManager.commitCount);
        assertFalse(outer.get().isRollbackOnly());
    }

    @Test
    void nested_failureRollsBackToTheSavepointAndTheOuterContinues() throws Exception {
        AtomicReference<TransactionStatus<String>> outer = new AtomicReference<>();

        String result = txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            outer.set(status);
            assertThrows(IllegalStateException.class, () ->
                    txManager.executeNested((innerResource, nested) -> {
                        throw new IllegalStateException("nested failure");
                    }));
            return "outer-survived";
        });

        assertEquals("outer-survived", result);
        assertEquals(1, txManager.savepointCount);
        assertEquals(1, txManager.rollbackToCount);
        assertEquals(0, txManager.releaseCount);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount, "the outer transaction must not roll back");
        assertFalse(outer.get().isRollbackOnly());
    }

    @Test
    void nested_rollbackOnlyStaysLocal() throws Exception {
        AtomicReference<TransactionStatus<String>> outer = new AtomicReference<>();

        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            outer.set(status);
            return txManager.executeNested((innerResource, nested) -> {
                nested.setRollbackOnly();
                return "nested";
            });
        });

        assertEquals(1, txManager.rollbackToCount, "a rollback-only nested section rolls back to its savepoint");
        assertEquals(1, txManager.commitCount, "the outer transaction still commits");
        assertEquals(0, txManager.rollbackCount);
        assertFalse(outer.get().isRollbackOnly());
    }

    @Test
    void nested_withoutTransaction_beginsAFullTransaction() throws Exception {
        String result = txManager.executeNested((resource, status) -> {
            assertTrue(status.isNewTransaction());
            assertFalse(status.isNestedTransaction());
            return "standalone";
        });

        assertEquals("standalone", result);
        assertEquals(1, txManager.beginCount);
        assertEquals(0, txManager.savepointCount);
        assertEquals(1, txManager.commitCount);
    }

    @Test
    void nested_withoutSavepointSupport_failsWithoutPoisoningTheOuterTransaction() throws Exception {
        txManager.supportsSavepoints = false;

        String result = txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            assertFalse(status.isSavepointSupported());
            assertThrows(NestedTransactionNotSupportedException.class, () ->
                    txManager.executeNested((innerResource, nested) -> "never"));
            return "outer-survived";
        });

        assertEquals("outer-survived", result);
        assertEquals(1, txManager.commitCount);
        assertEquals(0, txManager.rollbackCount);
    }

    // --- manual demarcation --------------------------------------------------

    @Test
    void getTransaction_beginsButDoesNotBindAScope() {
        TransactionStatus<String> status = txManager.getTransaction(TransactionDefinition.DEFAULT);

        assertTrue(status.isNewTransaction());
        assertSame(StubTransactionManager.RESOURCE, status.getResource());
        assertEquals(1, txManager.beginCount);
        assertFalse(TransactionStatusHolder.isBound(), "manual demarcation binds no scope");

        txManager.commit(status);
        assertEquals(1, txManager.commitCount);
        assertTrue(status.isCompleted());
    }

    @Test
    void getTransaction_supportsOutside_returnsTheNoTransactionStatus() {
        TransactionStatus<String> status = txManager.getTransaction(definition(Propagation.SUPPORTS));

        assertFalse(status.isActualTransactionActive());
        assertEquals(0, txManager.beginCount);

        txManager.commit(status);
        txManager.rollback(status);
        assertEquals(0, txManager.commitCount, "completing a no-transaction status is a no-op");
        assertEquals(0, txManager.rollbackCount);
    }

    @Test
    void getTransaction_mandatoryWithoutTransaction_throws() {
        assertThrows(IllegalTransactionStateException.class, () ->
                txManager.getTransaction(definition(Propagation.MANDATORY)));
    }

    @Test
    void getTransaction_requiresNewOrNotSupportedWhileActive_requiresExecute() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            assertThrows(TransactionUsageException.class, () ->
                    txManager.getTransaction(definition(Propagation.REQUIRES_NEW)));
            assertThrows(TransactionUsageException.class, () ->
                    txManager.getTransaction(definition(Propagation.NOT_SUPPORTED)));
            assertThrows(TransactionUsageException.class, () ->
                    txManager.getTransaction(definition(Propagation.NEVER)));
            return null;
        });

        txManager.rollback(outer);
    }

    @Test
    void getTransaction_nestedInside_beginsASavepointSection() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            TransactionStatus<String> nested = txManager.getTransaction(definition(Propagation.NESTED));

            assertTrue(nested.isNestedTransaction());
            assertEquals(1, txManager.savepointCount);

            txManager.commit(nested);
            assertEquals(1, txManager.releaseCount);
            assertSame(outer, TransactionStatusHolder.get());
            return null;
        });

        txManager.commit(outer);
    }

    @Test
    void commit_rejectsAStatusThisCallerDidNotStart() throws Exception {
        TransactionStatus<String> outer = txManager.getTransaction(TransactionDefinition.DEFAULT);

        TransactionStatusHolder.call(outer, () -> {
            TransactionStatus<String> joined = txManager.getTransaction(TransactionDefinition.DEFAULT);
            assertThrows(IllegalTransactionStateException.class, () -> txManager.commit(joined));
            return null;
        });

        txManager.rollback(outer);
    }

    @Test
    void commit_twice_throws() {
        TransactionStatus<String> status = txManager.getTransaction(TransactionDefinition.DEFAULT);
        txManager.commit(status);

        assertThrows(IllegalTransactionStateException.class, () -> txManager.commit(status));
    }

    @Test
    void rollback_completesWithTheRolledBackOutcome() {
        TransactionStatus<String> status = txManager.getTransaction(TransactionDefinition.DEFAULT);

        txManager.rollback(status);

        assertEquals(TransactionSynchronization.Status.ROLLED_BACK, completionStatus(status));
        assertEquals(1, txManager.rollbackCount);
    }

    // --- lookups -------------------------------------------------------------

    @Test
    void findTransactionStatus_isEmptyOutsideATransaction() {
        assertTrue(txManager.findTransactionStatus().isEmpty());
        assertNull(txManager.getResource());
    }

    @Test
    void findTransactionStatus_exposesTheAmbientTransaction() throws Exception {
        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            assertSame(status, txManager.findTransactionStatus().orElseThrow());
            assertSame(StubTransactionManager.RESOURCE, txManager.getResource());
            return null;
        });

        assertTrue(txManager.findTransactionStatus().isEmpty());
        assertNull(txManager.getResource());
    }

    @Test
    void findTransactionStatus_ignoresACompletedStatusEvenWhenItIsBound() throws Exception {
        AtomicReference<TransactionStatus<String>> seen = new AtomicReference<>();
        txManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
            seen.set(status);
            return null;
        });

        TransactionStatusHolder.call(seen.get(), () -> {
            assertSame(seen.get(), TransactionStatusHolder.get());
            assertTrue(txManager.findTransactionStatus().isEmpty(),
                    "a completed status is visible to callbacks but is not ambient");
            return null;
        });
    }

    // --- argument checks -----------------------------------------------------

    @Test
    void execute_rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> txManager.execute(null, (resource, status) -> null));
        assertThrows(NullPointerException.class,
                () -> txManager.execute(TransactionDefinition.DEFAULT, null));
    }

    // --- helpers -------------------------------------------------------------

    private static TransactionDefinition definition(Propagation propagation) {
        return TransactionDefinition.DEFAULT.withPropagation(propagation);
    }

    private static TransactionSynchronization.Status completionStatus(TransactionStatus<?> status) {
        return ((DefaultTransactionStatus<?>) status).getCompletionStatus();
    }

    private static TransactionSynchronization recorder(List<String> events) {
        return new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                events.add("beforeCommit:" + readOnly);
            }

            @Override
            public void beforeCompletion() {
                events.add("beforeCompletion");
            }

            @Override
            public void afterCommit() {
                events.add("afterCommit");
            }

            @Override
            public void afterCompletion(TransactionSynchronization.Status status) {
                events.add("afterCompletion:" + status);
            }
        };
    }

    private static final class CheckedFailure extends Exception {
        CheckedFailure(String message) {
            super(message);
        }
    }

    private static final class SampleError extends Error {
        SampleError(String message) {
            super(message);
        }
    }
}
