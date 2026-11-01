package io.github.fludakit.tx;

/**
 * Indicates a transaction was rolled back unexpectedly (for example, a participant marked it
 * rollback-only and the outer transaction then attempted to commit).
 */
public class UnexpectedRollbackException extends TransactionException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public UnexpectedRollbackException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause   the failure that caused it
     */
    public UnexpectedRollbackException(String message, Throwable cause) {
        super(message, cause);
    }
}
