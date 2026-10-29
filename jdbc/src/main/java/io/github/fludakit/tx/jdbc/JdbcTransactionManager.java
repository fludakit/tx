package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.JdbcSavepoint;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import io.github.fludakit.tx.support.TransactionSavepointManager;
import io.github.fludakit.tx.support.TransactionStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Resource-local {@link TransactionManager} for a JDBC {@link DataSource}.
 *
 * <p>Constructed from the raw {@code DataSource} (the connection pool). {@code getTransaction} opens
 * a connection, disables auto-commit, binds it to the returned {@link TransactionContext} under
 * {@code Connection.class}, and registers a callback that releases the connection on completion.
 * {@link #getResource()} hands callers the transaction-bound {@link Connection} for the current
 * transaction, so plain JDBC code running inside {@code execute()} receives it directly. Outside
 * any transaction, {@link #getResource()} returns {@code null}. Use {@link #getDataSource()} for
 * the raw pool or {@link TransactionAwareDataSourceProxy} for DataSource-based code that joins
 * via {@code getConnection()}.</p>
 */
public class JdbcTransactionManager implements TransactionManager<Connection> {

    private static final Logger LOG = Logger.getLogger(JdbcTransactionManager.class.getName());

    private final DataSource rawDataSource;
    private final TransactionAwareDataSourceProxy dataSourceProxy;

    private static final String SAVEPOINT_PREFIX = "fluda_sp_";

    private final AtomicInteger savepointCounter = new AtomicInteger();

    public JdbcTransactionManager(DataSource dataSource) {
        this.rawDataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dataSourceProxy = new TransactionAwareDataSourceProxy(this.rawDataSource);
    }

    public DataSource getDataSource() {
        return rawDataSource;
    }

    @Override
    public Connection getResource() {
        TransactionContext ctx = TransactionContextHolder.get();
        if (ctx == null) {
            return null;
        }
        // the Connection bound to the active transaction context — plain JDBC code running
        // inside execute() receives this directly; code outside any transaction gets null
        return ctx.getResource(Connection.class);
    }

    /**
     * JDBC savepoints over this transaction's connection: the manager-level savepoint capability
     * attached to the transaction status at begin.
     */
    private final class ConnectionSavepointManager implements TransactionSavepointManager {

        private final Connection connection;

        ConnectionSavepointManager(Connection connection) {
            this.connection = connection;
        }

        @Override
        public TransactionSavepoint createSavepoint() {
            try {
                String name = SAVEPOINT_PREFIX + savepointCounter.incrementAndGet();
                return new JdbcSavepoint(connection.setSavepoint(name), name);
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not create JDBC savepoint", ex);
            }
        }

        @Override
        public void rollbackToSavepoint(TransactionSavepoint savepoint) {
            try {
                connection.rollback(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not roll back to JDBC savepoint", ex);
            }
        }

        @Override
        public void releaseSavepoint(TransactionSavepoint savepoint) {
            try {
                connection.releaseSavepoint(((JdbcSavepoint) savepoint).jdbcSavepoint());
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not release JDBC savepoint", ex);
            }
        }
    }

    @Override
    public TransactionContext getTransaction(TransactionDefinition definition) {
        Connection connection;
        try {
            connection = rawDataSource.getConnection();
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not open JDBC connection for transaction", ex);
        }
        try {
            connection.setAutoCommit(false);
            if (definition.readOnly()) {
                // driver/pool hint; the cleanup restores the JDBC default before release
                connection.setReadOnly(true);
            }
        } catch (SQLException ex) {
            // release the pooled connection before translating — nothing is bound to clean it up yet
            try {
                connection.setAutoCommit(true);
                connection.close();
            } catch (SQLException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw new TransactionSystemException("Could not open JDBC connection for transaction", ex);
        }
        TransactionContext context = new TransactionContext(true, TransactionStatus.initial(
                definition, new ConnectionSavepointManager(connection)));
        context.bindResource(Connection.class, connection);
        context.registerCallback(new ConnectionCleanup(connection, definition.readOnly()));
        return context;
    }

    @Override
    public void commit(TransactionContext context) {
        Connection connection = boundConnection(context);
        try {
            connection.commit();
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not commit JDBC transaction", ex);
        }
    }

    @Override
    public void rollback(TransactionContext context) {
        Connection connection = boundConnection(context);
        try {
            connection.rollback();
        } catch (SQLException ex) {
            throw new TransactionSystemException("Could not roll back JDBC transaction", ex);
        }
    }

    private Connection boundConnection(TransactionContext context) {
        Connection connection = context.getResource(Connection.class);
        if (connection == null) {
            throw new IllegalStateException("No JDBC Connection bound to the active transaction");
        }
        return connection;
    }

    private static final class ConnectionCleanup implements TransactionCallback {

        private final Connection connection;
        private final boolean resetReadOnly;

        ConnectionCleanup(Connection connection, boolean resetReadOnly) {
            this.connection = connection;
            this.resetReadOnly = resetReadOnly;
        }

        @Override
        public void afterCompletion(CompletionStatus status) {
            try {
                if (!connection.getAutoCommit()) {
                    connection.setAutoCommit(true);
                }
                if (resetReadOnly) {
                    connection.setReadOnly(false);
                }
                connection.close();
            } catch (SQLException ex) {
                LOG.log(Level.WARNING, "Failed to reset and release JDBC Connection", ex);
            }
        }
    }
}
