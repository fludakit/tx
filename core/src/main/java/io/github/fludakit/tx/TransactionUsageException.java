package io.github.fludakit.tx;

/**
 * Thrown when a transaction is used in a way its propagation rules forbid — for example a
 * {@code NEVER} method invoked inside an active transaction, or a status completed by someone
 * who does not own it.
 */
public class TransactionUsageException extends TransactionException {

    public TransactionUsageException(String message) {
        super(message);
    }

    public TransactionUsageException(String message, Throwable cause) {
        super(message, cause);
    }
}
