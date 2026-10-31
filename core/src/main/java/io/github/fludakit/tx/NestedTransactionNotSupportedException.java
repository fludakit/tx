package io.github.fludakit.tx;

/**
 * Thrown when a {@code NESTED} boundary needs a savepoint but the transaction manager does not
 * support savepoints.
 */
public class NestedTransactionNotSupportedException extends TransactionException {

    public NestedTransactionNotSupportedException(String message) {
        super(message);
    }

    public NestedTransactionNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
