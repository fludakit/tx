package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.JdbcSavepoint;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.TransactionSavepoint;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
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
 * {@link #getResource(TransactionContext)} hands callers a transaction-aware {@code DataSource}
 * view whose {@code getConnection()} returns that bound connection (suppressing {@code close()})
 * so plain JDBC code joins the transaction transparently — also outside any transaction, where
 * it simply delegates to the pool.</p>
 */
public class DataSourceTransactionManager implements TransactionManager<DataSource> {

    private static final Logger LOG = Logger.getLogger(DataSourceTransactionManager.class.getName());

    private final DataSource dataSource;
    private final TransactionAwareDataSourceProxy awareDataSource;

    private static final String SAVEPOINT_PREFIX = "fluda_sp_";

    private final AtomicInteger savepointCounter = new AtomicInteger();

    public DataSourceTransactionManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.awareDataSource = new TransactionAwareDataSourceProxy(this.dataSource);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public DataSource getResource() {
        return awareDataSource;
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
            connection = dataSource.getConnection();
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
