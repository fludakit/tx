package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionCallback;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Resource-local {@link PlatformTransactionManager} for a JDBC {@link DataSource}.
 *
 * <p>Constructed from the raw {@code DataSource} (the connection pool). {@code getTransaction} opens
 * a connection, disables auto-commit, binds it to the returned {@link TransactionContext} under the
 * {@code DataSource} as key, and registers a callback that releases the connection on
 * completion. The companion {@link TransactionAwareDataSourceProxy} returns that bound connection
 * (suppressing {@code close()}) so plain JDBC code joins the transaction transparently.</p>
 */
public class DataSourceTransactionManager implements PlatformTransactionManager {

    private static final Logger LOG = Logger.getLogger(DataSourceTransactionManager.class.getName());

    private final DataSource dataSource;

    public DataSourceTransactionManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public DataSource getDataSource() {
        return dataSource;
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
        TransactionContext context = new TransactionContext(true);
        context.getResources().put(dataSource, connection);
        context.getCallbacks().add(new ConnectionCleanup(connection));
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
        Connection connection = context.getResource(dataSource, Connection.class);
        if (connection == null) {
            throw new IllegalStateException("No JDBC Connection bound for this DataSource");
        }
        return connection;
    }

    private static final class ConnectionCleanup implements TransactionCallback {

        private final Connection connection;

        ConnectionCleanup(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void afterCompletion(CompletionStatus status) {
            try {
                if (!connection.getAutoCommit()) {
                    connection.setAutoCommit(true);
                }
                connection.close();
            } catch (SQLException ex) {
                LOG.log(Level.WARNING, "Failed to reset and release JDBC Connection", ex);
            }
        }
    }
}
