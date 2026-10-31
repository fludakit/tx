package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSynchronization;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * {@link DataSource} decorator that makes a plain pool transaction-aware.
 *
 * <p>When an actual transaction is running, {@link #getConnection()} returns a close-suppressing
 * proxy over the transaction's connection. Outside one it delegates straight to the pool, and
 * {@code close()} stays real.</p>
 *
 * <h2>Finding the transaction's connection</h2>
 * <p>The connection is resolved from the current {@link TransactionStatus} in this order:</p>
 * <ol>
 *   <li>the status resource itself, when the transaction is JDBC-managed
 *       ({@code JdbcTransactionManager} binds a {@code Connection})</li>
 *   <li>the {@code Connection.class} attribute, when the transaction is managed by something
 *       else ({@code JpaTransactionManager} binds an {@code EntityManager} and exposes the
 *       underlying connection)</li>
 *   <li>otherwise the proxy joins the transaction: it opens a connection, disables auto-commit,
 *       binds it atomically, and commits/rolls it back with the transaction</li>
 * </ol>
 *
 * <p>A completed status is never joined: a leftover binding must not hand callers a connection
 * whose transaction already ended.</p>
 */
public class TransactionAwareDataSourceProxy implements DataSource {

    private static final Logger LOG = Logger.getLogger(TransactionAwareDataSourceProxy.class.getName());

    private final DataSource delegate;

    /**
     * @param delegate the raw pool whose connections join the running transaction
     */
    public TransactionAwareDataSourceProxy(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public Connection getConnection() throws SQLException {
        TransactionStatus<?> status = activeStatus();
        if (status == null) {
            return delegate.getConnection();
        }
        Connection connection = resolveConnection(status);
        if (connection == null) {
            connection = joinTransaction(status);
        }
        return closeSuppressingProxy(connection);
    }

    /**
     * As {@link #getConnection()}, ignoring the supplied credentials while a transaction is
     * running — the transaction's connection comes from the pool with the pool's credentials.
     * Outside a transaction the request goes straight to the delegate, credentials included.
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        TransactionStatus<?> status = activeStatus();
        if (status == null) {
            // no transaction to join: the credentials belong to the pool
            return delegate.getConnection(username, password);
        }
        Connection connection = resolveConnection(status);
        if (connection == null) {
            connection = joinTransaction(status);
        }
        return closeSuppressingProxy(connection);
    }

    private static TransactionStatus<?> activeStatus() {
        TransactionStatus<?> status = TransactionStatusHolder.get();
        if (status == null || !status.isActualTransactionActive() || status.isCompleted()) {
            return null;
        }
        return status;
    }

    private static Connection resolveConnection(TransactionStatus<?> status) {
        if (status.getResource() instanceof Connection connection) {
            return connection;
        }
        return status.getAttribute(Connection.class);
    }

    private Connection joinTransaction(TransactionStatus<?> status) throws SQLException {
        Connection connection = delegate.getConnection();
        try {
            connection.setAutoCommit(false);
            Connection bound = status.putAttributeIfAbsent(Connection.class, connection);
            if (bound != null) {
                // another participant bound a connection first - use the winner's and release ours
                JdbcConnections.close(connection);
                return bound;
            }
            status.registerSynchronization(new ConnectionJoining(connection));
            return connection;
        } catch (RuntimeException | SQLException ex) {
            JdbcConnections.close(connection);
            throw ex;
        }
    }

    private static Connection closeSuppressingProxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /**
     * Completes a connection this proxy joined: commits it on commit, rolls it back on rollback,
     * then resets and releases it.
     */
    private static final class ConnectionJoining implements TransactionSynchronization {

        private final Connection connection;

        ConnectionJoining(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            try {
                connection.commit();
            } catch (SQLException ex) {
                throw new TransactionSystemException("Could not commit the joined JDBC connection", ex);
            }
        }

        @Override
        public void afterCompletion(Status status) {
            try {
                if (status == Status.ROLLED_BACK && !connection.isClosed()) {
                    connection.rollback();
                }
            } catch (SQLException ex) {
                LOG.log(Level.WARNING, "Failed to complete the joined JDBC connection", ex);
            } finally {
                JdbcConnections.release(connection, false);
            }
        }
    }
}
