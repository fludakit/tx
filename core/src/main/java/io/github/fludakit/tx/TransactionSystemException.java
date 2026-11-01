package io.github.fludakit.tx;

/**
 * Wraps a failure while starting, committing, or rolling back a transaction.
 */
public class TransactionSystemException extends TransactionException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause   the failure that caused it
     */
    public TransactionSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
