package io.github.fludakit.tx;

import jakarta.transaction.TransactionalException;

/**
 * Base unchecked exception for transaction failures, replacing raw {@code throws Exception}.
 *
 * <p>Extends the spec's {@link TransactionalException} — which the Jakarta
 * {@code @Transactional} interceptor must throw — so both the declarative and the programmatic
 * front-ends report through one hierarchy: catching either type works on either path, and a
 * {@code TransactionException} thrown for {@code MANDATORY}/{@code NEVER} violations satisfies
 * the interceptor contract.</p>
 */
public class TransactionException extends TransactionalException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public TransactionException(String message) {
        super(message, null);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause   the failure that caused it
     */
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
