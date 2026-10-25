package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionCallback;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.*;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceTransactionManagerTest {

    @Test
    void getTransactionBindsConnectionWithAutoCommitDisabled() throws Exception {
        DataSource dataSource = newDataSource("dsmgr1");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);

        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        assertTrue(context.isActualTransactionActive());

        Connection bound = context.getResource(Connection.class);
        assertNotNull(bound);
        assertFalse(bound.getAutoCommit());
        bound.close();
    }

    @Test
    void commitAndRollbackDelegateToConnection() throws Exception {
        DataSource dataSource = newDataSource("dsmgr2");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);

        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        assertDoesNotThrow(() -> manager.commit(context));
        assertDoesNotThrow(() -> manager.rollback(context));

        Connection bound = context.getResource(Connection.class);
        bound.close();
    }

    @Test
    void afterCompletionResetsAndClosesConnection() throws Exception {
        DataSource dataSource = newDataSource("dsmgr3");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);

        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        Connection bound = context.getResource(Connection.class);

        context.getCallbacks()
                .forEach(s -> s.afterCompletion(TransactionCallback.CompletionStatus.COMMITTED));

        assertTrue(bound.isClosed());
    }

    @Test
    void getTransaction_autocommitFailure_closesConnectionAndThrows() throws Exception {
        // setAutoCommit(false) failing means nothing is bound to clean up yet - the manager
        // itself must release the pooled connection before translating
        DataSource dataSource = newDataSource("dsmgr4");
        ConnectionCounters counters = new ConnectionCounters();
        Connection pooled = dataSource.getConnection();
        DataSource failing = new StubDataSource(() -> new FailingConnection(pooled, counters));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(failing);

        assertThrows(TransactionSystemException.class, () ->
                manager.getTransaction(TransactionDefinition.DEFAULT));

        assertEquals(1, counters.closeCount, "the pooled connection must be closed on the failure path");
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    /** Counts close() calls through any connection handed out. */
    private static final class ConnectionCounters {
        int closeCount;
    }

    private static final class StubDataSource implements DataSource {
        private final java.util.function.Supplier<Connection> supplier;

        StubDataSource(java.util.function.Supplier<Connection> supplier) {
            this.supplier = supplier;
        }

        @Override
        public Connection getConnection() {
            return supplier.get();
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("stub"); }
    }

    /** A real H2 connection whose setAutoCommit(false) always fails, counting close(). */
    private static final class FailingConnection implements Connection {
        private final Connection delegate;
        private final ConnectionCounters counters;

        FailingConnection(Connection delegate, ConnectionCounters counters) {
            this.delegate = delegate;
            this.counters = counters;
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            if (!autoCommit) {
                throw new SQLException("simulated setAutoCommit(false) failure");
            }
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public void close() throws SQLException {
            counters.closeCount++;
            delegate.close();
        }

        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(java.util.Properties properties) throws SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
