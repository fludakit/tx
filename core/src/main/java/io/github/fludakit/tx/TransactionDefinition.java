package io.github.fludakit.tx;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutionException;

import jakarta.transaction.Transactional;

/**
 * Immutable transaction attributes, resolved from a {@link Transactional} annotation or built
 * programmatically.
 *
 * <p>A definition carries the propagation behaviour, the rollback rules, and the read-only
 * hint. It also owns the rollback decision itself — {@link #rollbackOn(Throwable)} — so the
 * declarative and the programmatic front-ends share one rule set and cannot drift.</p>
 *
 * <p>Defaults to {@link Propagation#REQUIRED}, no explicit rollback rules, and
 * {@code readOnly = false}. Every "modifier" method returns a copy; instances are immutable and
 * safe to share.</p>
 *
 * <h2>Rollback rules</h2>
 * <p>The defaults follow Jakarta Transactions (and Spring): a {@link RuntimeException} or an
 * {@link Error} rolls back, a checked exception does not. {@code dontRollbackOn} wins over
 * {@code rollbackOn} when both match; {@link InvocationTargetException} and
 * {@link ExecutionException} are unwrapped before classification, so the rule matches what the
 * caller actually threw.</p>
 *
 * @param propagation    how the boundary relates to a running transaction
 * @param rollbackOn     exception types that force a rollback
 * @param dontRollbackOn exception types that force a commit
 * @param readOnly       the read-only hint
 * @see Propagation
 */
public record TransactionDefinition(Propagation propagation,
                                    Class<? extends Throwable>[] rollbackOn,
                                    Class<? extends Throwable>[] dontRollbackOn,
                                    boolean readOnly) {

    private static final Class<? extends Throwable>[] NO_EXCEPTIONS = new Class[0];

    /** Default definition: {@code REQUIRED}, no rollback rules, writable. */
    public static final TransactionDefinition DEFAULT =
            new TransactionDefinition(Propagation.REQUIRED, NO_EXCEPTIONS, NO_EXCEPTIONS, false);

    /** {@code REQUIRED}, no rollback rules, read-only. */
    public static final TransactionDefinition READ_ONLY =
            new TransactionDefinition(Propagation.REQUIRED, NO_EXCEPTIONS, NO_EXCEPTIONS, true);

    public TransactionDefinition {
        rollbackOn = rollbackOn == null ? NO_EXCEPTIONS : rollbackOn.clone();
        dontRollbackOn = dontRollbackOn == null ? NO_EXCEPTIONS : dontRollbackOn.clone();
    }

    public TransactionDefinition(Propagation propagation,
                                 Class<? extends Throwable>[] rollbackOn,
                                 Class<? extends Throwable>[] dontRollbackOn) {
        this(propagation, rollbackOn, dontRollbackOn, false);
    }

    /**
     * Resolves the attributes of a {@link Transactional} annotation.
     *
     * @param annotation the annotation, or {@code null} for {@link #DEFAULT}
     */
    @SuppressWarnings("unchecked")
    public TransactionDefinition(Transactional annotation) {
        this(annotation == null ? Propagation.REQUIRED : Propagation.of(annotation.value()),
                annotation == null ? NO_EXCEPTIONS : (Class<? extends Throwable>[]) annotation.rollbackOn(),
                annotation == null ? NO_EXCEPTIONS : (Class<? extends Throwable>[]) annotation.dontRollbackOn(),
                false);
    }

    /** The default definition ({@code REQUIRED}, no rollback rules, writable). */
    public static TransactionDefinition withDefaults() {
        return DEFAULT;
    }

    /** A read-only definition with {@code REQUIRED} propagation. */
    public static TransactionDefinition readOnlyDefinition() {
        return READ_ONLY;
    }

    /** A copy of this definition with the given propagation behaviour. */
    public TransactionDefinition withPropagation(Propagation propagation) {
        return new TransactionDefinition(propagation, rollbackOn, dontRollbackOn, readOnly);
    }

    /** A copy of this definition with the given read-only hint. */
    public TransactionDefinition withReadOnly(boolean readOnly) {
        return new TransactionDefinition(propagation, rollbackOn, dontRollbackOn, readOnly);
    }

    /**
     * Whether the given failure must roll back the transaction.
     *
     * @param failure the failure escaping the unit of work — never {@code null}
     * @return {@code true} if the transaction must roll back
     */
    public boolean rollbackOn(Throwable failure) {
        Throwable candidate = unwrap(failure);
        for (Class<? extends Throwable> type : dontRollbackOn) {
            if (type.isAssignableFrom(candidate.getClass())) {
                return false;
            }
        }
        for (Class<? extends Throwable> type : rollbackOn) {
            if (type.isAssignableFrom(candidate.getClass())) {
                return true;
            }
        }
        return candidate instanceof RuntimeException || candidate instanceof Error;
    }

    /**
     * Unwraps reflective and asynchronous wrappers, whose own types say nothing about the
     * failure the application meant to raise.
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable candidate = failure;
        while (candidate.getCause() != null
                && (candidate instanceof InvocationTargetException
                || candidate instanceof ExecutionException)) {
            candidate = candidate.getCause();
        }
        return candidate;
    }
}
