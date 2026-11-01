package io.github.fludakit.tx;

/**
 * Wraps a failure while starting, committing, or rolling back a transaction.
 */
public class TransactionSystemException extends TransactionException {

    public TransactionSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
