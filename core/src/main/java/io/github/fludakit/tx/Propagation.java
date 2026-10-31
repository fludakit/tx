package io.github.fludakit.tx;

import jakarta.transaction.Transactional;

/**
 * How a transaction boundary relates to a transaction that is already running.
 *
 * <p>These are the six behaviours defined by {@link jakarta.transaction.Transactional.TxType},
 * plus {@link #NESTED}. Jakarta Transactions deliberately leaves {@code NESTED} out of the
 * annotation — the spec has no portable nested-transaction contract — but a resource-local
 * manager can still express it with savepoints, so the definition carries it. The CDI
 * interceptor maps the annotation's {@link Transactional.TxType} onto this enum; the extra
 * value is only reachable programmatically through
 * {@link TransactionalOperations#executeNested(TransactionExecution)}.</p>
 *
 * @see TransactionDefinition#propagation()
 * @see jakarta.transaction.Transactional.TxType
 */
public enum Propagation {

    /** Join the running transaction, or begin one if none is running. */
    REQUIRED,

    /** Always begin a new transaction, suspending any running one. */
    REQUIRES_NEW,

    /** Join the running transaction, or run without one. */
    SUPPORTS,

    /** Run without a transaction, suspending any running one. */
    NOT_SUPPORTED,

    /** Join the running transaction, failing if none is running. */
    MANDATORY,

    /** Run without a transaction, failing if one is running. */
    NEVER,

    /** Run in a savepoint-scoped section of the running transaction, or begin a transaction. */
    NESTED;

    /**
     * The propagation matching a Jakarta annotation value.
     *
     * @param type the annotation value — never {@code null}
     * @return the matching propagation
     */
    public static Propagation of(Transactional.TxType type) {
        return switch (type) {
            case REQUIRED -> REQUIRED;
            case REQUIRES_NEW -> REQUIRES_NEW;
            case SUPPORTS -> SUPPORTS;
            case NOT_SUPPORTED -> NOT_SUPPORTED;
            case MANDATORY -> MANDATORY;
            case NEVER -> NEVER;
        };
    }
}
