package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.SavepointManager;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;
import io.github.fludakit.tx.support.JdbcSavepointManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
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

    private final DataSource rawDataSource;
    private final TransactionAwareDataSourceProxy dataSourceProxy;

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
    public TransactionAwareDataSourceProxy getTransactionAwareDataSource() {
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
            JdbcConnections.release(connection, readOnly);
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
        return new JdbcSavepointManager(status::getResource);
    }

    private static Connection connected(DefaultTransactionStatus<Connection> status) {
        Connection connection = status.getResource();
        if (connection == null) {
            throw new IllegalStateException("No JDBC connection is bound to the transaction " + status);
        }
        return connection;
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
            JdbcConnections.release(connection, resetReadOnly);
        }
    }
}
