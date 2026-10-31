package io.github.fludakit.tx.support;

import io.github.fludakit.tx.IllegalTransactionStateException;
import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionException;
import io.github.fludakit.tx.TransactionExecution;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.TransactionUsageException;
import io.github.fludakit.tx.UnexpectedRollbackException;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The reusable half of a {@link TransactionManager}: propagation, suspension, lifecycle
 * dispatch, synchronizations, and completion state.
 *
 * <p>A transaction manager supplies resource primitives only — {@link #doBegin},
 * {@link #doCommit}, {@link #doRollback}, and optionally a {@link SavepointManager} — and this
 * class turns them into the propagation table, the begin/execute/complete state machine, and
 * the callback ordering. Both front-ends (the programmatic {@code execute} and the CDI
 * {@code @Transactional} interceptor) drive those same primitives, so the two paths cannot
 * drift.</p>
 *
 * <h2>What a manager must not do</h2>
 * <ul>
 *   <li>interpret propagation — this class decides join/suspend/begin</li>
 *   <li>bind or unbind the status — {@link TransactionStatusHolder} owns the scope</li>
 *   <li>trigger synchronizations — completion does that, in one place</li>
 *   <li>mark the status completed — completion does that, exactly once</li>
 * </ul>
 *
 * <h2>Failure handling</h2>
 * <p>When the unit of work fails, the definition's {@link TransactionDefinition#rollbackOn}
 * rules decide between commit and rollback, and the original failure is always propagated. If
 * completion itself then fails, the completion failure is attached as a suppressed exception to
 * the original one — the application failure stays the primary diagnosis.</p>
 *
 * <p>When completing a transaction fails in the other direction — a commit that throws — the
 * manager is asked to roll back, and the status ends {@code UNKNOWN} if even that fails. This
 * is a best-effort recovery, not a distributed guarantee.</p>
 *
 * @param <T> the resource type this manager binds
 * @see DefaultTransactionStatus
 * @see TransactionStatusHolder
 */
public abstract class AbstractTransactionOperations<T> implements TransactionManager<T> {

    private final Logger logger = Logger.getLogger(getClass().getName());

    // --- resource primitives -------------------------------------------------

    /**
     * Starts a transaction on the given status: acquire the resource, begin the resource
     * transaction, and bind the resource with {@link #bindResource(DefaultTransactionStatus,
     * Object)}.
     *
     * <p>If this method fails, it must clean up after itself — a half-open connection must not
     * survive the failure. The template marks the status completed but cannot release what it
     * never saw.</p>
     *
     * @param status the transaction to start
     */
    protected abstract void doBegin(DefaultTransactionStatus<T> status);

    /**
     * Commits the resource transaction carried by the given status.
     *
     * <p>The template has already checked the ownership and rollback-only flags; a straight
     * commit is all that is required.</p>
     *
     * @param status the transaction to commit
     */
    protected abstract void doCommit(DefaultTransactionStatus<T> status);

    /**
     * Rolls back the resource transaction carried by the given status.
     *
     * <p>The template has already checked the ownership flags; a straight rollback is all that
     * is required.</p>
     *
     * @param status the transaction to roll back
     */
    protected abstract void doRollback(DefaultTransactionStatus<T> status);

    // --- optional hooks ------------------------------------------------------

    /**
     * Begins a savepoint-scoped section.
     *
     * <p>The default creates a savepoint through the status and keeps it, which is exactly right
     * for JDBC. A manager with a different nesting mechanism overrides this (and the two methods
     * below) together.</p>
     *
     * @param status the nested status
     * @throws NestedTransactionNotSupportedException if the transaction has no savepoint support
     */
    protected void doNestedBegin(DefaultTransactionStatus<T> status) {
        if (!status.isSavepointSupported()) {
            throw new NestedTransactionNotSupportedException(
                    "Cannot begin a nested transaction - this transaction manager does not support savepoints");
        }
        status.setSavepoint(status.createSavepoint());
    }

    /**
     * Commits a savepoint-scoped section by releasing its savepoint, so its changes become part
     * of the surrounding transaction.
     *
     * @param status the nested status
     */
    protected void doNestedCommit(DefaultTransactionStatus<T> status) {
        TransactionSavepoint savepoint = status.getSavepoint();
        if (savepoint != null) {
            status.setSavepoint(null);
            status.releaseSavepoint(savepoint);
        }
    }

    /**
     * Rolls a savepoint-scoped section back to its savepoint, leaving the surrounding
     * transaction active.
     *
     * @param status the nested status
     */
    protected void doNestedRollback(DefaultTransactionStatus<T> status) {
        TransactionSavepoint savepoint = status.getSavepoint();
        if (savepoint != null) {
            status.setSavepoint(null);
            status.rollbackToSavepoint(savepoint);
        }
    }

    /**
     * Detaches the physical resource before the transaction is suspended.
     *
     * <p>A no-op by default: the status already carries its own resource, and the scope binding
     * shadows the suspended transaction. A manager that hands out a single shared resource (an
     * OSIV-managed {@code EntityManager}, for example) overrides this and {@link #doResume}.</p>
     *
     * @param status the transaction being suspended
     */
    protected void doSuspend(DefaultTransactionStatus<T> status) {
    }

    /**
     * Reattaches the physical resource after a suspension ends.
     *
     * @param status the transaction being resumed
     */
    protected void doResume(DefaultTransactionStatus<T> status) {
    }

    /**
     * Supplies the savepoint capability for a transaction this manager is about to begin.
     *
     * <p>Called once per new transaction, after the status exists and before
     * {@link #doBegin(DefaultTransactionStatus)}, so the manager can capture the status and
     * resolve the resource lazily — at the time a savepoint is actually created the resource is
     * bound. Returning {@code null} (the default) means the manager does not support
     * savepoints, and {@code NESTED} propagation fails with
     * {@link NestedTransactionNotSupportedException}.</p>
     *
     * @param status the transaction the capability belongs to
     * @return the savepoint manager, or {@code null} when unsupported
     */
    protected SavepointManager newSavepointManager(DefaultTransactionStatus<T> status) {
        return null;
    }

    // --- programmatic boundaries ---------------------------------------------

    @Override
    public <R> R execute(TransactionDefinition definition, TransactionExecution<T, R> execution) throws Exception {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(execution, "execution must not be null");

        DefaultTransactionStatus<T> existing = currentTransaction();
        return switch (definition.propagation()) {
            case REQUIRED -> existing == null
                    ? executeInNewTransaction(definition, execution)
                    : executeJoined(existing, definition, execution);
            case SUPPORTS -> existing == null
                    ? executeWithoutTransaction(execution)
                    : executeJoined(existing, definition, execution);
            case MANDATORY -> {
                if (existing == null) {
                    throw new IllegalTransactionStateException(
                            "No existing transaction found for transaction marked with propagation 'mandatory'");
                }
                yield executeJoined(existing, definition, execution);
            }
            case REQUIRES_NEW -> existing == null
                    ? executeInNewTransaction(definition, execution)
                    : executeSuspending(existing, definition, execution);
            case NOT_SUPPORTED -> existing == null
                    ? executeWithoutTransaction(execution)
                    : executeSuspendingWithoutTransaction(existing, execution);
            case NEVER -> {
                if (existing != null) {
                    throw new TransactionUsageException(
                            "Existing transaction found for transaction marked with propagation 'never'");
                }
                yield executeWithoutTransaction(execution);
            }
            case NESTED -> existing == null
                    ? executeInNewTransaction(definition, execution)
                    : executeNestedSection(existing, definition, execution);
        };
    }

    @Override
    public Optional<TransactionStatus<T>> findTransactionStatus() {
        DefaultTransactionStatus<T> status = currentTransaction();
        return status == null ? Optional.empty() : Optional.of(status);
    }

    @Override
    public T getResource() {
        DefaultTransactionStatus<T> status = currentTransaction();
        return status == null ? null : status.getResource();
    }

    // --- manual demarcation --------------------------------------------------

    @Override
    public TransactionStatus<T> getTransaction(TransactionDefinition definition) throws TransactionException {
        Objects.requireNonNull(definition, "definition must not be null");

        DefaultTransactionStatus<T> existing = currentTransaction();
        if (existing == null) {
            return switch (definition.propagation()) {
                case REQUIRED, REQUIRES_NEW, NESTED -> openNewTransaction(definition);
                case SUPPORTS, NOT_SUPPORTED, NEVER -> DefaultTransactionStatus.noTransaction();
                case MANDATORY -> throw new IllegalTransactionStateException(
                        "No existing transaction found for transaction marked with propagation 'mandatory'");
            };
        }
        return switch (definition.propagation()) {
            case REQUIRED, SUPPORTS, MANDATORY -> DefaultTransactionStatus.joined(existing, definition);
            case NESTED -> {
                DefaultTransactionStatus<T> nested = createNestedStatus(existing, definition);
                begin(nested);
                yield nested;
            }
            case REQUIRES_NEW, NOT_SUPPORTED -> throw new TransactionUsageException(
                    "Propagation " + definition.propagation() + " suspends the current transaction and "
                            + "therefore requires a scope - use execute(...) instead of getTransaction(...)");
            case NEVER -> throw new TransactionUsageException(
                    "Existing transaction found for transaction marked with propagation 'never'");
        };
    }

    @Override
    public void commit(TransactionStatus<T> status) throws TransactionException {
        Objects.requireNonNull(status, "status must not be null");
        if (!status.isActualTransactionActive()) {
            // a no-transaction status (SUPPORTS, NOT_SUPPORTED, NEVER outside a transaction) has
            // nothing to complete
            return;
        }
        commitInternal(owned(status));
    }

    @Override
    public void rollback(TransactionStatus<T> status) throws TransactionException {
        Objects.requireNonNull(status, "status must not be null");
        if (!status.isActualTransactionActive()) {
            return;
        }
        rollbackInternal(owned(status));
    }

    // --- binding for managers ------------------------------------------------

    /**
     * Binds the resource a transaction operates on. Called by {@link #doBegin} implementations
     * once the resource is acquired.
     *
     * @param status   the transaction being started
     * @param resource the resource it is bound to
     */
    protected final void bindResource(DefaultTransactionStatus<T> status, T resource) {
        status.setResource(Objects.requireNonNull(resource, "resource must not be null"));
    }

    // --- execution paths -----------------------------------------------------

    private <R> R executeInNewTransaction(TransactionDefinition definition,
                                          TransactionExecution<T, R> execution) throws Exception {
        DefaultTransactionStatus<T> status = createTransactionStatus(definition);
        return TransactionStatusHolder.call(status, () -> {
            begin(status);
            return completeAround(status, execution);
        });
    }

    private <R> R executeJoined(DefaultTransactionStatus<T> enclosing,
                                TransactionDefinition definition,
                                TransactionExecution<T, R> execution) throws Exception {
        // a participant gets its own view (its definition, its rollback rules) but shares the
        // running transaction's resource, attributes, and synchronizations
        DefaultTransactionStatus<T> status = DefaultTransactionStatus.joined(enclosing, definition);
        return TransactionStatusHolder.call(status, () -> execution.call(status.getResource(), status));
    }

    private <R> R executeWithoutTransaction(TransactionExecution<T, R> execution) throws Exception {
        // bind the no-transaction status so lookups inside the unit of work see "no transaction"
        // rather than the suspended outer one
        DefaultTransactionStatus<T> status = DefaultTransactionStatus.noTransaction();
        return TransactionStatusHolder.call(status, () -> execution.call(null, status));
    }

    private <R> R executeSuspending(DefaultTransactionStatus<T> outer,
                                    TransactionDefinition definition,
                                    TransactionExecution<T, R> execution) throws Exception {
        doSuspend(outer);
        try {
            return executeInNewTransaction(definition, execution);
        } finally {
            doResume(outer);
        }
    }

    private <R> R executeSuspendingWithoutTransaction(DefaultTransactionStatus<T> outer,
                                                      TransactionExecution<T, R> execution) throws Exception {
        doSuspend(outer);
        try {
            return executeWithoutTransaction(execution);
        } finally {
            doResume(outer);
        }
    }

    private <R> R executeNestedSection(DefaultTransactionStatus<T> enclosing,
                                       TransactionDefinition definition,
                                       TransactionExecution<T, R> execution) throws Exception {
        DefaultTransactionStatus<T> status = createNestedStatus(enclosing, definition);
        return TransactionStatusHolder.call(status, () -> {
            begin(status);
            return completeAround(status, execution);
        });
    }

    /**
     * Runs the unit of work and completes the transaction it owns: commit on normal completion,
     * the definition's rollback decision on failure.
     */
    private <R> R completeAround(DefaultTransactionStatus<T> status,
                                 TransactionExecution<T, R> execution) throws Exception {
        R result;
        try {
            result = execution.call(status.getResource(), status);
        } catch (Throwable failure) {
            try {
                if (status.getDefinition().rollbackOn(failure)) {
                    rollbackInternal(status);
                } else {
                    commitInternal(status);
                }
            } catch (RuntimeException | Error completionFailure) {
                // keep the application failure as the primary diagnosis
                failure.addSuppressed(completionFailure);
            }
            throw failure;
        }
        commitInternal(status);
        return result;
    }

    // --- status creation -----------------------------------------------------

    private DefaultTransactionStatus<T> createTransactionStatus(TransactionDefinition definition) {
        DefaultTransactionStatus<T> status = DefaultTransactionStatus.newTransaction(definition, null);
        status.setSavepointManager(newSavepointManager(status));
        return status;
    }

    private DefaultTransactionStatus<T> openNewTransaction(TransactionDefinition definition) {
        DefaultTransactionStatus<T> status = createTransactionStatus(definition);
        begin(status);
        return status;
    }

    private DefaultTransactionStatus<T> createNestedStatus(DefaultTransactionStatus<T> enclosing,
                                                           TransactionDefinition definition) {
        if (!enclosing.isSavepointSupported()) {
            throw new NestedTransactionNotSupportedException(
                    "Cannot create a nested transaction - this transaction manager does not support savepoints");
        }
        return DefaultTransactionStatus.nested(enclosing, definition);
    }

    private void begin(DefaultTransactionStatus<T> status) {
        try {
            if (status.isNewTransaction()) {
                logger.fine(() -> "Beginning transaction " + status);
                doBegin(status);
            } else if (status.isNestedTransaction()) {
                logger.fine(() -> "Beginning nested transaction " + status);
                doNestedBegin(status);
            }
        } catch (RuntimeException | Error failure) {
            // the status must never stay usable: nothing was started, so nothing will complete
            finish(status, TransactionSynchronization.Status.UNKNOWN);
            throw failure;
        }
    }

    // --- completion ----------------------------------------------------------

    private void commitInternal(DefaultTransactionStatus<T> status) {
        if (status.isCompleted()) {
            throw new IllegalTransactionStateException(
                    "Transaction is already completed - do not call commit or rollback more than once per transaction");
        }
        if (status.isLocalRollbackOnly()) {
            // the boundary itself asked for a rollback: commit() honors it silently
            rollbackInternal(status);
            return;
        }
        if (status.isGlobalRollbackOnly()) {
            // a participant asked for a rollback, so this commit cannot happen: roll back and say so
            rollbackInternal(status);
            throw new UnexpectedRollbackException(
                    "Transaction rolled back because it has been marked as rollback-only");
        }

        logger.fine(() -> "Committing transaction " + status);
        boolean beforeCompletionInvoked = false;
        try {
            status.triggerBeforeCommit(status.getDefinition().readOnly());
            status.triggerBeforeCompletion();
            beforeCompletionInvoked = true;

            if (status.isNewTransaction()) {
                doCommit(status);
            } else if (status.isNestedTransaction()) {
                doNestedCommit(status);
            }
        } catch (RuntimeException | Error failure) {
            if (!beforeCompletionInvoked) {
                try {
                    status.triggerBeforeCompletion();
                } catch (RuntimeException | Error beforeCompletionFailure) {
                    failure.addSuppressed(beforeCompletionFailure);
                }
            }
            rollbackAfterCommitFailure(status, failure);
            throw failure;
        }

        // the transaction did commit: afterCommit failures must not undo that conclusion, and
        // afterCompletion still has to run
        status.markCompleted(TransactionSynchronization.Status.COMMITTED);
        try {
            status.triggerAfterCommit();
        } finally {
            status.triggerAfterCompletion(TransactionSynchronization.Status.COMMITTED);
        }
    }

    private void rollbackInternal(DefaultTransactionStatus<T> status) {
        if (status.isCompleted()) {
            throw new IllegalTransactionStateException(
                    "Transaction is already completed - do not call commit or rollback more than once per transaction");
        }

        logger.fine(() -> "Rolling back transaction " + status);
        try {
            status.triggerBeforeCompletion();
            if (status.isNewTransaction()) {
                doRollback(status);
            } else if (status.isNestedTransaction()) {
                doNestedRollback(status);
            }
        } catch (RuntimeException | Error failure) {
            finish(status, TransactionSynchronization.Status.UNKNOWN);
            throw failure;
        }
        finish(status, TransactionSynchronization.Status.ROLLED_BACK);
    }

    /**
     * Best-effort recovery after a commit failed: roll the resource back, then report the
     * outcome the resource actually reached.
     */
    private void rollbackAfterCommitFailure(DefaultTransactionStatus<T> status, Throwable failure) {
        logger.log(Level.FINE, "Commit failed - rolling back " + status, failure);
        try {
            if (status.isNewTransaction()) {
                doRollback(status);
            } else if (status.isNestedTransaction()) {
                doNestedRollback(status);
            }
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            finish(status, TransactionSynchronization.Status.UNKNOWN);
            return;
        }
        finish(status, TransactionSynchronization.Status.ROLLED_BACK);
    }

    private void finish(DefaultTransactionStatus<T> status, TransactionSynchronization.Status outcome) {
        if (!status.isCompleted()) {
            status.markCompleted(outcome);
        }
        status.triggerAfterCompletion(outcome);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * The ambient transaction, or {@code null}. A status that already completed is not ambient:
     * completion callbacks can still see it through {@link TransactionStatusHolder}, but a new
     * boundary must not join it.
     */
    @SuppressWarnings("unchecked")
    private DefaultTransactionStatus<T> currentTransaction() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        if (status == null || !status.isActualTransactionActive() || status.isCompleted()) {
            return null;
        }
        if (status instanceof DefaultTransactionStatus<?> ours) {
            return (DefaultTransactionStatus<T>) ours;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private DefaultTransactionStatus<T> owned(TransactionStatus<T> status) {
        if (!(status instanceof DefaultTransactionStatus<?> ours)) {
            throw new TransactionUsageException(
                    "The transaction status was not created by this transaction manager");
        }
        if (!status.isNewTransaction() && !status.isNestedTransaction()) {
            throw new IllegalTransactionStateException(
                    "The transaction status belongs to a transaction this caller did not start - "
                            + "only the boundary that began a transaction may commit or roll it back");
        }
        return (DefaultTransactionStatus<T>) ours;
    }
}
