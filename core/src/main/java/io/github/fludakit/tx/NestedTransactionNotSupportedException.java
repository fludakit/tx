package io.github.fludakit.tx;

/**
 * Thrown when a {@code NESTED} boundary needs a savepoint but the transaction manager does not
 * support savepoints.
 */
public class NestedTransactionNotSupportedException extends TransactionException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public NestedTransactionNotSupportedException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause   the failure that caused it
     */
    public NestedTransactionNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
