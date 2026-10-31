package io.github.fludakit.tx.support;

import io.github.fludakit.tx.IllegalTransactionStateException;
import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The status implementation used by {@link AbstractTransactionOperations}: one transaction, of
 * one of four kinds.
 *
 * <table border="1">
 *   <caption>Status kinds</caption>
 *   <tr><th>Kind</th><th>Created by</th><th>Behaviour</th></tr>
 *   <tr><td>new</td><td>a boundary that starts the transaction</td>
 *       <td>owns the resource, the synchronizations, the savepoint manager, and completion</td></tr>
 *   <tr><td>joined</td><td>a participant that reuses a running transaction</td>
 *       <td>delegates to the running transaction; marking it rollback-only marks the running
 *           transaction globally rollback-only</td></tr>
 *   <tr><td>nested</td><td>a {@code NESTED} section</td>
 *       <td>delegates to the running transaction except for its own savepoint; its rollback-only
 *           flag stays local</td></tr>
 *   <tr><td>no transaction</td><td>{@code SUPPORTS}, {@code NOT_SUPPORTED}, {@code NEVER}</td>
 *       <td>shared immutable sentinel: no resource, no synchronizations, no completion</td></tr>
 * </table>
 *
 * <p>A joined or nested status deliberately does not keep its own resource or synchronization
 * list. A participant cannot release a resource it did not open, and a callback registered
 * inside a participant must fire once — at the real boundary.</p>
 *
 * @param <T> the resource type
 */
public abstract class DefaultTransactionStatus<T> implements TransactionStatus<T> {

    private final TransactionDefinition definition;
    private final Map<Class<?>, Object> attributes = new ConcurrentHashMap<>();
    private final List<TransactionSynchronization> synchronizations = new CopyOnWriteArrayList<>();

    private T resource;
    private SavepointManager savepointManager;
    private TransactionSavepoint savepoint;
    private boolean localRollbackOnly;
    private boolean globalRollbackOnly;
    private boolean completed;
    private TransactionSynchronization.Status completionStatus;

    private DefaultTransactionStatus(TransactionDefinition definition) {
        this.definition = definition;
    }

    // --- factories ---

    /** A status for a transaction this boundary begins. */
    public static <T> DefaultTransactionStatus<T> newTransaction(TransactionDefinition definition,
                                                                 SavepointManager savepointManager) {
        NewTransactionStatus<T> status = new NewTransactionStatus<>(definition);
        status.setSavepointManager(savepointManager);
        return status;
    }

    /** A status for a participant that reuses the running transaction. */
    public static <T> DefaultTransactionStatus<T> joined(DefaultTransactionStatus<T> enclosing,
                                                         TransactionDefinition definition) {
        return new JoinedTransactionStatus<>(definition, enclosing);
    }

    /** A status for a savepoint-scoped section inside the running transaction. */
    public static <T> DefaultTransactionStatus<T> nested(DefaultTransactionStatus<T> enclosing,
                                                         TransactionDefinition definition) {
        return new NestedTransactionStatus<>(definition, enclosing);
    }

    /** The shared status for a scope without a transaction. */
    public static <T> DefaultTransactionStatus<T> noTransaction() {
        return NoTransactionStatus.instance();
    }

    // --- definition and resource ---

    @Override
    public TransactionDefinition getDefinition() {
        return definition;
    }

    @Override
    public T getResource() {
        return resource;
    }

    void setResource(T resource) {
        this.resource = resource;
    }

    // --- attributes ---

    @Override
    public <A> A getAttribute(Class<A> type) {
        return type.cast(attributes.get(type));
    }

    @Override
    public <A> A putAttributeIfAbsent(Class<A> type, A value) {
        requireLive("putAttributeIfAbsent");
        return type.cast(attributes.putIfAbsent(type, value));
    }

    // --- transaction shape ---

    @Override
    public boolean isNewTransaction() {
        return false;
    }

    @Override
    public boolean isNestedTransaction() {
        return false;
    }

    @Override
    public boolean isActualTransactionActive() {
        return true;
    }

    // --- rollback-only ---

    @Override
    public boolean isRollbackOnly() {
        return isLocalRollbackOnly() || isGlobalRollbackOnly();
    }

    @Override
    public boolean isLocalRollbackOnly() {
        return localRollbackOnly;
    }

    @Override
    public boolean isGlobalRollbackOnly() {
        return globalRollbackOnly;
    }

    @Override
    public void setRollbackOnly() {
        requireLive("setRollbackOnly");
        localRollbackOnly = true;
    }

    /** Marks the transaction rollback-only on behalf of a participant. */
    void markGlobalRollbackOnly() {
        globalRollbackOnly = true;
    }

    // --- completion ---

    @Override
    public boolean isCompleted() {
        return completed;
    }

    /** The outcome once completed, or {@code null} while active. */
    public TransactionSynchronization.Status getCompletionStatus() {
        return completionStatus;
    }

    void markCompleted(TransactionSynchronization.Status status) {
        if (completed) {
            throw new IllegalTransactionStateException(
                    "Transaction is already completed - do not call commit or rollback more than once per transaction");
        }
        completed = true;
        completionStatus = status;
    }

    // --- savepoints ---

    @Override
    public boolean isSavepointSupported() {
        return savepointManager != null;
    }

    @Override
    public TransactionSavepoint createSavepoint() {
        requireLive("createSavepoint");
        if (savepointManager == null) {
            throw new io.github.fludakit.tx.NestedTransactionNotSupportedException(
                    "This transaction has no savepoint support (the manager supplied no SavepointManager)");
        }
        return savepointManager.createSavepoint();
    }

    @Override
    public void rollbackToSavepoint(TransactionSavepoint savepoint) {
        requireSavepoints().rollbackToSavepoint(savepoint);
    }

    @Override
    public void releaseSavepoint(TransactionSavepoint savepoint) {
        requireSavepoints().releaseSavepoint(savepoint);
    }

    SavepointManager requireSavepoints() {
        if (savepointManager == null) {
            throw new io.github.fludakit.tx.NestedTransactionNotSupportedException(
                    "This transaction has no savepoint support (the manager supplied no SavepointManager)");
        }
        return savepointManager;
    }

    void setSavepointManager(SavepointManager savepointManager) {
        this.savepointManager = savepointManager;
    }

    TransactionSavepoint getSavepoint() {
        return savepoint;
    }

    void setSavepoint(TransactionSavepoint savepoint) {
        this.savepoint = savepoint;
    }

    // --- synchronizations ---

    @Override
    public void registerSynchronization(TransactionSynchronization synchronization) {
        requireLive("registerSynchronization");
        synchronizations.add(synchronization);
    }

    void triggerBeforeCommit(boolean readOnly) {
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.beforeCommit(readOnly);
        }
    }

    void triggerBeforeCompletion() {
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.beforeCompletion();
        }
    }

    void triggerAfterCommit() {
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.afterCommit();
        }
    }

    void triggerAfterCompletion(TransactionSynchronization.Status status) {
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.afterCompletion(status);
        }
    }

    // --- guards ---

    protected void requireLive(String operation) {
        if (completed) {
            throw new IllegalTransactionStateException(
                    operation + " is not allowed on a completed transaction");
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{definition=" + definition
                + ", resource=" + resource
                + ", rollbackOnly=" + isRollbackOnly()
                + ", completed=" + completed
                + (completionStatus == null ? "" : ", outcome=" + completionStatus)
                + '}';
    }

    // --- kinds ---

    private static final class NewTransactionStatus<T> extends DefaultTransactionStatus<T> {

        private NewTransactionStatus(TransactionDefinition definition) {
            super(definition);
        }

        @Override
        public boolean isNewTransaction() {
            return true;
        }
    }

    /**
     * A participant view over the running transaction. It owns nothing: reads delegate, and a
     * rollback-only mark is escalated to the running transaction so its boundary cannot commit.
     */
    private static final class JoinedTransactionStatus<T> extends DefaultTransactionStatus<T> {

        private final DefaultTransactionStatus<T> enclosing;

        private JoinedTransactionStatus(TransactionDefinition definition, DefaultTransactionStatus<T> enclosing) {
            super(definition);
            this.enclosing = enclosing;
        }

        @Override
        public TransactionDefinition getDefinition() {
            return enclosing.getDefinition();
        }

        @Override
        public T getResource() {
            return enclosing.getResource();
        }

        @Override
        public <A> A getAttribute(Class<A> type) {
            return enclosing.getAttribute(type);
        }

        @Override
        public <A> A putAttributeIfAbsent(Class<A> type, A value) {
            return enclosing.putAttributeIfAbsent(type, value);
        }

        @Override
        public boolean isCompleted() {
            return super.isCompleted() || enclosing.isCompleted();
        }

        @Override
        public void setRollbackOnly() {
            if (isCompleted()) {
                throw new IllegalTransactionStateException(
                        "setRollbackOnly is not allowed on a completed transaction");
            }
            // the participant owns nothing: the running transaction is the one that must not commit
            enclosing.markGlobalRollbackOnly();
        }

        @Override
        public boolean isLocalRollbackOnly() {
            return enclosing.isLocalRollbackOnly();
        }

        @Override
        public boolean isGlobalRollbackOnly() {
            return enclosing.isGlobalRollbackOnly();
        }

        @Override
        public void registerSynchronization(TransactionSynchronization synchronization) {
            enclosing.registerSynchronization(synchronization);
        }

        @Override
        public boolean isSavepointSupported() {
            return enclosing.isSavepointSupported();
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            return enclosing.createSavepoint();
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            enclosing.rollbackToSavepoint(savepoint);
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            enclosing.releaseSavepoint(savepoint);
        }

        @Override
        public String toString() {
            return "JoinedTransactionStatus{enclosing=" + enclosing + '}';
        }
    }

    /**
     * A savepoint-scoped section. It shares the running transaction's resource, attributes, and
     * synchronizations, but keeps its own savepoint and its own rollback-only flag: a nested
     * rollback must not poison the surrounding transaction.
     */
    private static final class NestedTransactionStatus<T> extends DefaultTransactionStatus<T> {

        private final DefaultTransactionStatus<T> enclosing;

        private NestedTransactionStatus(TransactionDefinition definition, DefaultTransactionStatus<T> enclosing) {
            super(definition);
            this.enclosing = enclosing;
        }

        @Override
        public T getResource() {
            return enclosing.getResource();
        }

        @Override
        public <A> A getAttribute(Class<A> type) {
            return enclosing.getAttribute(type);
        }

        @Override
        public <A> A putAttributeIfAbsent(Class<A> type, A value) {
            return enclosing.putAttributeIfAbsent(type, value);
        }

        @Override
        public boolean isNestedTransaction() {
            return true;
        }

        @Override
        public boolean isGlobalRollbackOnly() {
            // a rollback-only participant anywhere poisons the running transaction, so the nested
            // section must report it even though its own rollback flag stays local
            return enclosing.isGlobalRollbackOnly();
        }

        @Override
        public boolean isCompleted() {
            return super.isCompleted() || enclosing.isCompleted();
        }

        @Override
        public void registerSynchronization(TransactionSynchronization synchronization) {
            enclosing.registerSynchronization(synchronization);
        }

        @Override
        public boolean isSavepointSupported() {
            return enclosing.isSavepointSupported();
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            return enclosing.createSavepoint();
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            enclosing.rollbackToSavepoint(savepoint);
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            enclosing.releaseSavepoint(savepoint);
        }

        @Override
        public String toString() {
            return "NestedTransactionStatus{enclosing=" + enclosing + '}';
        }
    }

    /**
     * The shared no-transaction status. It is bound for the duration of a {@code SUPPORTS},
     * {@code NOT_SUPPORTED}, or {@code NEVER} scope so that lookups see "there is no
     * transaction" instead of the suspended outer one, and it rejects every mutation — it is a
     * singleton, so a mutation would leak into every later scope.
     */
    private static final class NoTransactionStatus<T> extends DefaultTransactionStatus<T> {

        private static final NoTransactionStatus<?> INSTANCE =
                new NoTransactionStatus<>(TransactionDefinition.DEFAULT);

        @SuppressWarnings("unchecked")
        static <T> NoTransactionStatus<T> instance() {
            return (NoTransactionStatus<T>) INSTANCE;
        }

        private NoTransactionStatus(TransactionDefinition definition) {
            super(definition);
        }

        @Override
        public T getResource() {
            return null;
        }

        @Override
        public <A> A getAttribute(Class<A> type) {
            return null;
        }

        @Override
        public <A> A putAttributeIfAbsent(Class<A> type, A value) {
            throw new IllegalTransactionStateException("No transaction is active");
        }

        @Override
        public boolean isActualTransactionActive() {
            return false;
        }

        @Override
        public boolean isRollbackOnly() {
            return false;
        }

        @Override
        public boolean isLocalRollbackOnly() {
            return false;
        }

        @Override
        public void setRollbackOnly() {
            throw new IllegalTransactionStateException("No transaction is active");
        }

        @Override
        public boolean isSavepointSupported() {
            return false;
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            throw new io.github.fludakit.tx.NestedTransactionNotSupportedException("No transaction is active");
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            throw new io.github.fludakit.tx.NestedTransactionNotSupportedException("No transaction is active");
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            throw new io.github.fludakit.tx.NestedTransactionNotSupportedException("No transaction is active");
        }

        @Override
        public void registerSynchronization(TransactionSynchronization synchronization) {
            throw new IllegalTransactionStateException(
                    "No transaction is active - there is no transaction to register a synchronization on");
        }

        @Override
        public String toString() {
            return "NoTransactionStatus{}";
        }
    }
}
