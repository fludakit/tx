package io.github.fludakit.tx;

/**
 * One transaction: the handle carried from the boundary that began it to every participant that
 * joins it.
 *
 * <p>A status is the single object per transaction. It knows the transaction definition, the
 * resource the transaction is bound to, whether it started the transaction or joined one, the
 * rollback-only flags, the registered {@link TransactionSynchronization synchronizations}, the
 * optional savepoint, and the completion state. It is what the boundary binds to the current
 * scope, what a unit of work receives, and what a {@code @TransactionScoped} bean hangs off.</p>
 *
 * <h2>Ownership</h2>
 * <p>{@link #isNewTransaction()} tells the caller whether completion belongs to it. Only the
 * boundary that began the transaction — the one that received {@code true} — commits or rolls it
 * back. A participant sees {@code false} and only reads the status, marks it rollback-only, or
 * registers a synchronization.</p>
 *
 * <h2>Resources</h2>
 * <p>{@link #getResource()} is the resource the transaction is bound to: a {@code Connection}
 * for the JDBC manager, an {@code EntityManager} for the JPA manager, {@code null} outside an
 * actual transaction. {@link #getAttribute(Class)} / {@link #putAttributeIfAbsent(Class, Object)}
 * are additional typed slots for the two features that need per-transaction state
 * ({@code @TransactionScoped} bean stores, transactional CDI events). They are not a general
 * purpose map.</p>
 *
 * @param <T> the resource type the manager binds
 * @see TransactionManager
 * @see TransactionSynchronization
 */
public interface TransactionStatus<T> {

    /**
     * The definition this transaction was begun (or joined) with.
     *
     * @return the transaction attributes
     */
    TransactionDefinition getDefinition();

    /**
     * The resource the transaction is bound to, or {@code null} outside an actual transaction.
     *
     * @return the bound resource, or {@code null}
     */
    T getResource();

    /**
     * A per-transaction typed attribute, or {@code null} when none is set.
     *
     * <p>Reads stay available after completion: completion callbacks
     * ({@code afterCommit}, {@code afterCompletion}) still need to reach per-transaction state,
     * such as a {@code @TransactionScoped} bean store that has to be destroyed.</p>
     *
     * @param type the attribute type
     * @param <A>  the attribute type
     * @return the bound value, or {@code null} when none is set
     */
    <A> A getAttribute(Class<A> type);

    /**
     * Binds an attribute unless one is already bound, returning the existing value when another
     * participant won the race.
     *
     * <p>Atomic on purpose: two threads joining the same transaction must not both install (and
     * later both release) a per-transaction object.</p>
     *
     * @param type  the attribute type
     * @param value the value to bind
     * @param <A>   the attribute type
     * @return {@code null} if this call bound the value, otherwise the value already bound
     * @throws IllegalTransactionStateException if the status is completed
     */
    <A> A putAttributeIfAbsent(Class<A> type, A value);

    /**
     * Whether this status belongs to a transaction that was begun here (not joined).
     *
     * @return {@code true} if completion belongs to the caller
     */
    boolean isNewTransaction();

    /**
     * Whether this status scopes a savepoint section inside an outer transaction.
     *
     * @return {@code true} for a nested status
     */
    boolean isNestedTransaction();

    /**
     * Whether an actual resource transaction is running — {@code false} for the no-transaction
     * status.
     *
     * @return {@code true} if a resource transaction is active
     */
    boolean isActualTransactionActive();

    /**
     * Whether the transaction is marked rollback-only, locally or by a participant.
     *
     * @return {@code true} if either rollback-only flag is set
     */
    boolean isRollbackOnly();

    /**
     * Whether the boundary that holds this status marked it rollback-only.
     *
     * @return {@code true} if the local flag is set
     */
    boolean isLocalRollbackOnly();

    /**
     * Whether a participant asked for a rollback, so the boundary must not commit.
     *
     * @return {@code true} if the global flag is set
     */
    boolean isGlobalRollbackOnly();

    /**
     * Marks the transaction rollback-only: it will roll back even if the unit of work returns
     * normally.
     *
     * @throws IllegalTransactionStateException if the status is completed
     */
    void setRollbackOnly();

    /**
     * Whether the transaction already completed (committed, rolled back, or unknown).
     *
     * @return {@code true} once completion ran
     */
    boolean isCompleted();

    /**
     * Whether the underlying manager supports savepoints for this transaction.
     *
     * @return {@code true} if savepoint operations are available
     */
    boolean isSavepointSupported();

    /**
     * Creates a savepoint at the transaction's current point.
     *
     * @return the savepoint handle
     * @throws NestedTransactionNotSupportedException if savepoints are not supported
     */
    TransactionSavepoint createSavepoint();

    /**
     * Undoes every change made after the savepoint; the transaction stays active.
     *
     * @param savepoint the handle returned by {@link #createSavepoint()}
     */
    void rollbackToSavepoint(TransactionSavepoint savepoint);

    /**
     * Releases the savepoint: its changes become part of the transaction.
     *
     * @param savepoint the handle returned by {@link #createSavepoint()}
     */
    void releaseSavepoint(TransactionSavepoint savepoint);

    /**
     * Registers a lifecycle participant on the transaction that is actually running. In a joined
     * or nested call this registers on the enclosing transaction, so the callbacks fire once, at
     * the real boundary.
     *
     * @param synchronization the participant
     */
    void registerSynchronization(TransactionSynchronization synchronization);
}
