package io.github.fludakit.tx;

import jakarta.transaction.Transactional;

/**
 * Immutable transaction attributes, built from a {@link Transactional} annotation and consumed by
 * {@link PlatformTransactionManager#getTransaction(TransactionDefinition)}.
 *
 * <p>Defaults to {@code REQUIRED} with empty rollback rules and {@code readOnly = false}.</p>
 */
public record TransactionDefinition(Transactional.TxType propagation,
                                    Class<? extends Throwable>[] rollbackOn,
                                    Class<? extends Throwable>[] dontRollbackOn,
                                    boolean readOnly) {

    private static final Class<? extends Throwable>[] EMPTY = new Class[0];

    public static final TransactionDefinition DEFAULT =
            new TransactionDefinition(Transactional.TxType.REQUIRED, EMPTY, EMPTY, false);

    private static final TransactionDefinition READ_ONLY =
            new TransactionDefinition(Transactional.TxType.REQUIRED, EMPTY, EMPTY, true);

    /**
     * Returns a transaction definition with default settings (REQUIRED propagation, no rollback rules).
     *
     * @return a default transaction definition
     */
    public static TransactionDefinition withDefaults() {
        return DEFAULT;
    }

    /**
     * Returns a read-only transaction definition with REQUIRED propagation.
     *
     * <p>Read-only is a hint to the transaction manager; support varies by implementation.</p>
     *
     * @return a read-only transaction definition
     */
    public static TransactionDefinition readOnlyDefinition() {
        return READ_ONLY;
    }

    public TransactionDefinition {
        rollbackOn = rollbackOn == null ? EMPTY : rollbackOn;
        dontRollbackOn = dontRollbackOn == null ? EMPTY : dontRollbackOn;
    }

    public TransactionDefinition(Transactional.TxType propagation,
                                 Class<? extends Throwable>[] rollbackOn,
                                 Class<? extends Throwable>[] dontRollbackOn) {
        this(propagation, rollbackOn, dontRollbackOn, false);
    }

    public TransactionDefinition(Transactional annotation) {
        this(annotation == null ? Transactional.TxType.REQUIRED : annotation.value(),
                annotation == null ? EMPTY : annotation.rollbackOn(),
                annotation == null ? EMPTY : annotation.dontRollbackOn(),
                false);
    }
}
