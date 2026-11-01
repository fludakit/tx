package io.github.fludakit.tx;

/**
 * Thrown when a transaction is in the wrong state for the requested operation — a
 * {@code MANDATORY} boundary without a transaction, or a transaction completed twice.
 */
public class IllegalTransactionStateException extends TransactionException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public IllegalTransactionStateException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause   the failure that caused it
     */
    public IllegalTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
