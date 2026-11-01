package io.github.fludakit.tx;

/**
 * Thrown when a transaction is used in a way its propagation rules forbid — for example a
 * {@code NEVER} method invoked inside an active transaction, or a status completed by someone
 * who does not own it.
 */
public class TransactionUsageException extends TransactionException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public TransactionUsageException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause   the failure that caused it
     */
    public TransactionUsageException(String message, Throwable cause) {
        super(message, cause);
    }
}
