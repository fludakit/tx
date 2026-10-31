package io.github.fludakit.tx;

/**
 * Thrown when a transaction is in the wrong state for the requested operation — a
 * {@code MANDATORY} boundary without a transaction, or a transaction completed twice.
 */
public class IllegalTransactionStateException extends TransactionException {

    public IllegalTransactionStateException(String message) {
        super(message);
    }

    public IllegalTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
