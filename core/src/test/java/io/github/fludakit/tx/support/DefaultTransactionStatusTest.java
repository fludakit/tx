package io.github.fludakit.tx.support;

import io.github.fludakit.tx.IllegalTransactionStateException;
import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.Propagation;
import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSynchronization;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the four {@link DefaultTransactionStatus} kinds: the boundary's own transaction,
 * the participant view, the savepoint section, and the shared no-transaction sentinel.
 */
class DefaultTransactionStatusTest {

    private static final TransactionDefinition DEFINITION = TransactionDefinition.DEFAULT;
    private static final TransactionDefinition NESTED_DEFINITION =
            TransactionDefinition.DEFAULT.withPropagation(Propagation.NESTED);

    // --- new transaction -----------------------------------------------------

    @Test
    void newTransaction_isTheBoundarysOwn() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, null);

        assertSame(DEFINITION, status.getDefinition());
        assertTrue(status.isNewTransaction());
        assertFalse(status.isNestedTransaction());
        assertTrue(status.isActualTransactionActive());
        assertNull(status.getResource());
        assertFalse(status.isRollbackOnly());
        assertFalse(status.isCompleted());
        assertNull(status.getCompletionStatus());
        assertFalse(status.isSavepointSupported());
    }

    @Test
    void newTransaction_attributesAreTypedAndFirstWriterWins() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, null);

        assertNull(status.putAttributeIfAbsent(String.class, "first"));
        assertEquals("first", status.getAttribute(String.class));
        assertEquals("first", status.putAttributeIfAbsent(String.class, "second"));
        assertEquals("first", status.getAttribute(String.class));
        assertNull(status.getAttribute(Integer.class));
    }

    @Test
    void newTransaction_rollbackOnlyStartsLocal() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, null);

        status.setRollbackOnly();

        assertTrue(status.isRollbackOnly());
        assertTrue(status.isLocalRollbackOnly());
        assertFalse(status.isGlobalRollbackOnly());
    }

    @Test
    void newTransaction_savepointsDelegateToTheManager() {
        RecordingSavepointManager savepoints = new RecordingSavepointManager();
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, savepoints);

        assertTrue(status.isSavepointSupported());

        TransactionSavepoint savepoint = status.createSavepoint();
        assertEquals("sp1", savepoint.name());
        status.rollbackToSavepoint(savepoint);
        status.releaseSavepoint(savepoint);

        assertEquals(1, savepoints.created);
        assertEquals(1, savepoints.rolledBack);
        assertEquals(1, savepoints.released);
    }

    @Test
    void newTransaction_withoutSavepointSupport_rejectsSavepointOperations() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, null);
        TransactionSavepoint savepoint = new RecordingSavepointManager().createSavepoint();

        assertFalse(status.isSavepointSupported());
        assertThrows(NestedTransactionNotSupportedException.class, status::createSavepoint);
        assertThrows(NestedTransactionNotSupportedException.class,
                () -> status.rollbackToSavepoint(savepoint));
        assertThrows(NestedTransactionNotSupportedException.class,
                () -> status.releaseSavepoint(savepoint));
    }

    @Test
    void completedStatus_rejectsEveryMutation() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, null);
        status.markCompleted(TransactionSynchronization.Status.COMMITTED);

        assertTrue(status.isCompleted());
        assertEquals(TransactionSynchronization.Status.COMMITTED, status.getCompletionStatus());
        assertThrows(IllegalTransactionStateException.class, status::setRollbackOnly);
        assertThrows(IllegalTransactionStateException.class,
                () -> status.putAttributeIfAbsent(String.class, "value"));
        assertThrows(IllegalTransactionStateException.class,
                () -> status.registerSynchronization(new TransactionSynchronization() {
                }));
        assertThrows(IllegalTransactionStateException.class, status::createSavepoint);
        assertThrows(IllegalTransactionStateException.class,
                () -> status.markCompleted(TransactionSynchronization.Status.ROLLED_BACK));
    }

    @Test
    void completedStatus_keepsAttributesReadableForCompletionCallbacks() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.newTransaction(DEFINITION, null);
        status.putAttributeIfAbsent(String.class, "value");

        status.markCompleted(TransactionSynchronization.Status.COMMITTED);

        assertEquals("value", status.getAttribute(String.class));
    }

    // --- joined participant --------------------------------------------------

    @Test
    void joinedStatus_isAViewOverTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        DefaultTransactionStatus<String> joined =
                DefaultTransactionStatus.joined(enclosing, DEFINITION.withReadOnly(true));

        assertFalse(joined.isNewTransaction());
        assertFalse(joined.isNestedTransaction());
        assertTrue(joined.isActualTransactionActive());
        assertSame("resource", joined.getResource());
        assertSame(DEFINITION, joined.getDefinition(), "the running transaction's definition wins");
        assertEquals("attribute", joined.getAttribute(String.class));
        assertEquals("attribute", joined.putAttributeIfAbsent(String.class, "other"));
        assertEquals("attribute", enclosing.getAttribute(String.class));
    }

    @Test
    void joinedStatus_rollbackOnlyEscalatesToTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        DefaultTransactionStatus<String> joined = DefaultTransactionStatus.joined(enclosing, DEFINITION);

        joined.setRollbackOnly();

        assertTrue(joined.isRollbackOnly());
        assertTrue(joined.isGlobalRollbackOnly());
        assertTrue(enclosing.isRollbackOnly());
        assertTrue(enclosing.isGlobalRollbackOnly());
        assertFalse(enclosing.isLocalRollbackOnly(), "the boundary itself did not ask for the rollback");
    }

    @Test
    void joinedStatus_registersSynchronizationsOnTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        DefaultTransactionStatus<String> joined = DefaultTransactionStatus.joined(enclosing, DEFINITION);
        List<TransactionSynchronization.Status> outcomes = new ArrayList<>();

        joined.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(TransactionSynchronization.Status status) {
                outcomes.add(status);
            }
        });
        enclosing.triggerAfterCompletion(TransactionSynchronization.Status.COMMITTED);

        assertEquals(List.of(TransactionSynchronization.Status.COMMITTED), outcomes,
                "the callback must fire once, at the real boundary");
    }

    @Test
    void joinedStatus_delegatesSavepoints() {
        RecordingSavepointManager savepoints = new RecordingSavepointManager();
        DefaultTransactionStatus<String> enclosing = startedTransaction(savepoints);
        DefaultTransactionStatus<String> joined = DefaultTransactionStatus.joined(enclosing, DEFINITION);

        assertTrue(joined.isSavepointSupported());
        joined.createSavepoint();

        assertEquals(1, savepoints.created);
    }

    @Test
    void joinedStatus_completionFollowsTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        DefaultTransactionStatus<String> joined = DefaultTransactionStatus.joined(enclosing, DEFINITION);

        enclosing.markCompleted(TransactionSynchronization.Status.ROLLED_BACK);

        assertTrue(joined.isCompleted());
        assertThrows(IllegalTransactionStateException.class, joined::setRollbackOnly);
    }

    // --- nested section ------------------------------------------------------

    @Test
    void nestedStatus_sharesTheTransactionButKeepsItsOwnSavepointAndRollbackFlag() {
        RecordingSavepointManager savepoints = new RecordingSavepointManager();
        DefaultTransactionStatus<String> enclosing = startedTransaction(savepoints);
        DefaultTransactionStatus<String> nested =
                DefaultTransactionStatus.nested(enclosing, NESTED_DEFINITION);

        assertFalse(nested.isNewTransaction());
        assertTrue(nested.isNestedTransaction());
        assertSame("resource", nested.getResource());
        assertSame(NESTED_DEFINITION, nested.getDefinition());
        assertEquals("attribute", nested.getAttribute(String.class));

        nested.setRollbackOnly();

        assertTrue(nested.isLocalRollbackOnly());
        assertTrue(nested.isRollbackOnly());
        assertFalse(nested.isGlobalRollbackOnly());
        assertFalse(enclosing.isRollbackOnly(), "a nested rollback must not poison the outer transaction");
    }

    @Test
    void nestedStatus_reportsAGlobalRollbackOnlyFromTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        enclosing.markGlobalRollbackOnly();
        DefaultTransactionStatus<String> nested =
                DefaultTransactionStatus.nested(enclosing, NESTED_DEFINITION);

        assertTrue(nested.isGlobalRollbackOnly());
        assertTrue(nested.isRollbackOnly());
        assertFalse(nested.isLocalRollbackOnly());
    }

    @Test
    void nestedStatus_registersSynchronizationsOnTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        DefaultTransactionStatus<String> nested = DefaultTransactionStatus.nested(enclosing, NESTED_DEFINITION);
        List<TransactionSynchronization.Status> outcomes = new ArrayList<>();

        nested.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(TransactionSynchronization.Status status) {
                outcomes.add(status);
            }
        });
        enclosing.triggerAfterCompletion(TransactionSynchronization.Status.ROLLED_BACK);

        assertEquals(List.of(TransactionSynchronization.Status.ROLLED_BACK), outcomes);
    }

    @Test
    void nestedStatus_keepsItsOwnSavepointHandle() {
        RecordingSavepointManager savepoints = new RecordingSavepointManager();
        DefaultTransactionStatus<String> enclosing = startedTransaction(savepoints);
        DefaultTransactionStatus<String> nested = DefaultTransactionStatus.nested(enclosing, NESTED_DEFINITION);

        nested.setSavepoint(nested.createSavepoint());

        assertEquals("sp1", nested.getSavepoint().name());
        assertNull(enclosing.getSavepoint());
    }

    @Test
    void nestedStatus_completionFollowsTheRunningTransaction() {
        DefaultTransactionStatus<String> enclosing = startedTransaction();
        DefaultTransactionStatus<String> nested = DefaultTransactionStatus.nested(enclosing, NESTED_DEFINITION);

        enclosing.markCompleted(TransactionSynchronization.Status.COMMITTED);

        assertTrue(nested.isCompleted());
    }

    // --- no transaction ------------------------------------------------------

    @Test
    void noTransactionStatus_isASharedImmutableSentinel() {
        DefaultTransactionStatus<String> first = DefaultTransactionStatus.noTransaction();
        DefaultTransactionStatus<String> second = DefaultTransactionStatus.noTransaction();

        assertSame(first, second);
        assertSame(TransactionDefinition.DEFAULT, first.getDefinition());
        assertFalse(first.isActualTransactionActive());
        assertFalse(first.isNewTransaction());
        assertFalse(first.isNestedTransaction());
        assertNull(first.getResource());
        assertNull(first.getAttribute(String.class));
        assertFalse(first.isRollbackOnly());
        assertFalse(first.isSavepointSupported());
        assertFalse(first.isCompleted());
    }

    @Test
    void noTransactionStatus_rejectsEveryMutation() {
        DefaultTransactionStatus<String> status = DefaultTransactionStatus.noTransaction();

        assertThrows(IllegalTransactionStateException.class, status::setRollbackOnly);
        assertThrows(IllegalTransactionStateException.class,
                () -> status.putAttributeIfAbsent(String.class, "value"));
        assertThrows(IllegalTransactionStateException.class,
                () -> status.registerSynchronization(new TransactionSynchronization() {
                }));
        assertThrows(NestedTransactionNotSupportedException.class, status::createSavepoint);
    }

    // --- helpers -------------------------------------------------------------

    private static DefaultTransactionStatus<String> startedTransaction() {
        return startedTransaction(null);
    }

    private static DefaultTransactionStatus<String> startedTransaction(SavepointManager savepoints) {
        DefaultTransactionStatus<String> status =
                DefaultTransactionStatus.newTransaction(DEFINITION, savepoints);
        status.setResource("resource");
        status.putAttributeIfAbsent(String.class, "attribute");
        return status;
    }

    private static final class RecordingSavepointManager implements SavepointManager {

        int created;
        int rolledBack;
        int released;

        @Override
        public TransactionSavepoint createSavepoint() {
            created++;
            String name = "sp" + created;
            return () -> name;
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            rolledBack++;
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            released++;
        }
    }
}
