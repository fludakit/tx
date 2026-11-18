package io.github.fludakit.tx.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection lifecycle helpers for the JDBC transaction manager and
 * {@link TransactionAwareDataSourceProxy}.
 *
 * <p>Every connection those callers hand out comes from a pool, so "closing" it really means
 * returning it in a state the next borrower can use: auto-commit restored, the read-only hint
 * cleared when it was set, and only then {@code close()} called. Failing to reset is logged, not
 * thrown — a broken reset must not mask the transaction's own outcome — and {@code close()} always
 * runs.</p>
 */
public final class JdbcConnections {

    private static final Logger LOG = Logger.getLogger(JdbcConnections.class.getName());

    private JdbcConnections() {
        throw new AssertionError("No instances");
    }

    /**
     * Resets the pool-level connection state and releases the connection.
     *
     * @param connection    the connection to release
     * @param resetReadOnly whether the read-only hint was set and must be cleared
     */
    public static void release(Connection connection, boolean resetReadOnly) {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            if (resetReadOnly && !connection.isClosed()) {
                connection.setReadOnly(false);
            }
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, "Failed to reset the JDBC connection before release", ex);
        } finally {
            close(connection);
        }
    }

    /** Closes the connection, logging rather than throwing a failure. */
    public static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ex) {
            LOG.log(Level.WARNING, "Failed to release the JDBC connection", ex);
        }
    }
}
