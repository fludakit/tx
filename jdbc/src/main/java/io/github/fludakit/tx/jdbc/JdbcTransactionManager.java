package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.JdbcSavepoint;
import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Resource-local transaction manager for a JDBC {@link DataSource}.
 *
 * <p>The manager owns the connection lifecycle and nothing else: {@link #doBegin} opens a
 * connection from the pool, disables auto-commit (and applies the read-only hint), binds it to
 * the status, and registers a synchronization that resets and releases it. The template in
 * {@link AbstractTransactionOperations} owns propagation, nesting, and completion.</p>
 *
 * <p>Plain JDBC code running inside a boundary receives the bound {@link Connection} through
 * {@link io.github.fludakit.tx.TransactionalOperations#getResource()} or the resource handed to
 * {@code execute}. Pool-based code that calls {@link DataSource#getConnection()} itself should
 * use {@link #getTransactionAwareDataSource()}, whose connections join the running transaction
 * and suppress {@code close()}.</p>
 *
 * <h2>Savepoints</h2>
 * <p>JDBC savepoints back {@code NESTED} propagation. The capability is attached at begin time
 * and resolves the connection lazily, so it is available to every nested section of the
 * transaction.</p>
 */
public class JdbcTransactionManager extends AbstractTransactionOperations<Connection> {

    private static final Logger LOG = Logger.getLogger(JdbcTransactionManager.class.getName());
    private static final String SAVEPOINT_PREFIX = "fluda_sp_";

    private final DataSource rawDataSource;
    private final TransactionAwareDataSourceProxy dataSourceProxy;
    private final AtomicInteger savepointCounter = new AtomicInteger();

    /**
     * Creates a manager over the given pool.
     *
     * @param dataSource the raw {@code DataSource} — the connection pool
     */
    public JdbcTransactionManager(DataSource dataSource) {
        this.rawDataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dataSourceProxy = new TransactionAwareDataSourceProxy(this.rawDataSource);
    }

    /** The raw pool this manager opens connections from. */
    public DataSource getDataSource() {
        return rawDataSource;
    }

    /**
     * A view of the pool whose {@link DataSource#getConnection()} returns the transaction-bound
     * connection and suppresses {@code close()} while a transaction is active.
     */
    public DataSource getTransactionAwareDataSource() {
        return dataSourceProxy;
    }

    @Override
    protected void doBegin(DefaultTransactionStatus<Connection> status) {
        Connection connection;
        try {
            connection = rawDataSource.getConnection();
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not open a JDBC connection for the transaction", ex);
        }

        boolean readOnly = status.getDefinition().readOnly();
        try {
            connection.setAutoCommit(false);
            if (readOnly) {
                // driver/pool hint only - JDBC read-only is not a transaction attribute
                connection.setReadOnly(true);
            }
        } catch (SQLException ex) {
            // nothing is bound to clean this connection up yet, so release it here
            releaseQuietly(connection, readOnly, ex);
            throw new TransactionSystemException("Could not begin the JDBC transaction", ex);
        }

        bindResource(status, connection);
        status.registerSynchronization(new ConnectionCleanup(connection, readOnly));
    }

    @Override
    protected void doCommit(DefaultTransactionStatus<Connection> status) {
        try {
            connected(status).commit();
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not commit the JDBC transaction", ex);
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus<Connection> status) {
        try {
            connected(status).rollback();
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not roll back the JDBC transaction", ex);
        }
    }

    @Override
    protected SavepointManager newSavepointManager(DefaultTransactionStatus<Connection> status) {
        return new ConnectionSavepointManager(status::getResource);
    }

    private static Connection connected(DefaultTransactionStatus<Connection> status) {
        Connection connection = status.getResource();
        if (connection == null) {
            throw new IllegalStateException("No JDBC connection is bound to the transaction " + status);
        }
        return connection;
    }

    private static void releaseQuietly(Connection connection, boolean readOnly, SQLException failure) {
        try {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            if (readOnly) {
                connection.setReadOnly(false);
            }
            connection.close();
        } catch (SQLException suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    /**
     * Releases the pooled connection when the transaction completes, whatever the outcome.
     *
     * <p>Registered as a synchronization rather than a template hook so cleanup follows the same
     * path — and the same ordering — for every manager.</p>
     */
    private static final class ConnectionCleanup implements TransactionSynchronization {

        private final Connection connection;
        private final boolean resetReadOnly;

        ConnectionCleanup(Connection connection, boolean resetReadOnly) {
            this.connection = connection;
            this.resetReadOnly = resetReadOnly;
        }

        @Override
        public void afterCompletion(Status status) {
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
                try {
                    connection.close();
                } catch (SQLException ex) {
                    LOG.log(Level.WARNING, "Failed to release the JDBC connection", ex);
                }
            }
        }
    }

    /** JDBC savepoints over the connection of the transaction this manager belongs to. */
    private final class ConnectionSavepointManager implements SavepointManager {

        private final Supplier<Connection> connection;

        ConnectionSavepointManager(Supplier<Connection> connection) {
            this.connection = connection;
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            String name = SAVEPOINT_PREFIX + savepointCounter.incrementAndGet();
            try {
                return new JdbcSavepoint(connected().setSavepoint(name), name);
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not create a JDBC savepoint", ex);
            }
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            try {
                connected().rollback(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not roll back to the JDBC savepoint", ex);
            }
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            try {
                connected().releaseSavepoint(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not release the JDBC savepoint", ex);
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
    }
}
