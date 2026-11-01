package io.github.fludakit.tx.support;

import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSystemException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBC savepoints over the connection of one transaction.
 *
 * <p>The connection is resolved lazily through a {@link Supplier}: the manager is created before
 * the transaction begins (it is handed to the status), but a savepoint is only ever created while
 * the transaction runs, when the connection is bound. That keeps the capability available to every
 * {@code NESTED} section of the transaction without the manager knowing how the connection is
 * obtained — the JDBC manager reads the status resource, the JPA manager reads the
 * {@code Connection.class} attribute extracted from the {@code EntityManager}.</p>
 *
 * <p>{@link #releaseSavepoint(TransactionSavepoint)} tolerates drivers that reject
 * {@link Connection#releaseSavepoint(java.sql.Savepoint)}: some report
 * {@link SQLFeatureNotSupportedException}, some answer with a plain {@link SQLException} carrying
 * {@code "This operation is not supported."}. Releasing is only an optimization — the savepoint
 * disappears with the transaction either way — so the manager logs and continues instead of
 * failing a nested commit the driver already accepted.</p>
 *
 * <p>Savepoint names come from a per-manager counter and carry a fixed prefix, so several
 * managers over the same connection cannot collide and a failing section is easy to spot in a
 * driver log.</p>
 */
public final class JdbcSavepointManager implements SavepointManager {

    private static final Logger LOG = Logger.getLogger(JdbcSavepointManager.class.getName());

    // Names a savepoint so a conflicting name from another manager is recognisable in a driver log
    private static final String NAME_PREFIX = "fluda_sp_";

    // Message SQL Server (and a few other drivers) report from releaseSavepoint
    private static final String OPERATION_NOT_SUPPORTED = "This operation is not supported.";

    private final Supplier<Connection> connection;
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * Creates a manager over a lazily resolved connection.
     *
     * @param connection supplies the transaction's connection, or {@code null} when none is bound
     */
    public JdbcSavepointManager(Supplier<Connection> connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public TransactionSavepoint createSavepoint() {
        String name = NAME_PREFIX + counter.incrementAndGet();
        try {
            return new JdbcSavepoint(connected().setSavepoint(name), name);
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not create the JDBC savepoint '" + name + "'", ex);
        }
    }

    @Override
    public void rollbackToSavepoint(TransactionSavepoint savepoint) {
        try {
            connected().rollback(jdbcSavepoint(savepoint));
        } catch (SQLException ex) {
            throw new TransactionSystemException(
                    "Could not roll back to the JDBC savepoint '" + savepoint.name() + "'", ex);
        }
    }

    @Override
    public void releaseSavepoint(TransactionSavepoint savepoint) {
        try {
            connected().releaseSavepoint(jdbcSavepoint(savepoint));
        } catch (SQLException ex) {
            if (isUnsupportedOperation(ex)) {
                LOG.log(Level.FINE, "The JDBC driver does not support releasing savepoints - ignoring release of ''{0}''",
                        savepoint.name());
                return;
            }
            throw new TransactionSystemException(
                    "Could not release the JDBC savepoint '" + savepoint.name() + "'", ex);
        }
    }

    private Connection connected() {
        Connection bound = connection.get();
        if (bound == null) {
            throw new NestedTransactionNotSupportedException(
                    "No JDBC connection is bound to the transaction - savepoints are unavailable");
        }
        return bound;
    }

    private static Savepoint jdbcSavepoint(TransactionSavepoint savepoint) {
        if (savepoint instanceof JdbcSavepoint jdbc) {
            return jdbc.jdbcSavepoint();
        }
        throw new TransactionSystemException(
                "The savepoint '" + savepoint.name() + "' was not created by a JDBC savepoint manager", null);
    }

    private static boolean isUnsupportedOperation(SQLException exception) {
        if (exception instanceof SQLFeatureNotSupportedException) {
            return true;
        }
        return OPERATION_NOT_SUPPORTED.equals(exception.getMessage());
    }
}
