package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.support.AbstractTransactionOperations;
import io.github.fludakit.tx.support.DefaultTransactionStatus;
import io.github.fludakit.tx.support.TransactionStatusHolder;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TransactionAwareDataSourceProxy}: the three ways it resolves a
 * connection — the JDBC manager's resource, another manager's {@code Connection.class}
 * attribute, and joining the transaction itself — plus the guarantee that a completed
 * transaction never hands out its connection.
 */
class TransactionAwareDataSourceProxyTest {

    @Test
    void delegatesOutsideTransaction() throws Exception {
        DataSource dataSource = newDataSource("proxy1");
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);

        Connection connection = proxy.getConnection();
        assertFalse(connection.isClosed());
        connection.close();
        assertTrue(connection.isClosed(), "outside a transaction close must be real");
    }

    @Test
    void returnsTheTransactionConnectionAndSuppressesClose() throws Exception {
        DataSource dataSource = newDataSource("proxy2");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        DataSource proxy = manager.getTransactionAwareDataSource();
        AtomicReference<Connection> bound = new AtomicReference<>();

        manager.execute((connection, status) -> {
            bound.set(connection);

            try (Connection got = proxy.getConnection()) {
                assertFalse(got.isClosed());
                String catalog = connection.getCatalog();
                assertEquals(catalog, got.getCatalog(), "the proxy must wrap the transaction's connection");
            }
            assertFalse(connection.isClosed(), "close must be suppressed inside a transaction");

            Connection credentialed = proxy.getConnection("sa", "");
            credentialed.close();
            assertFalse(connection.isClosed());
            return null;
        });

        assertTrue(bound.get().isClosed(), "the transaction still releases its own connection");
    }

    @Test
    void resolvesTheConnectionStoredAsAnAttribute() throws Exception {
        DataSource dataSource = newDataSource("proxy3");
        createTable(dataSource);
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
        ResourceOnlyManager manager = new ResourceOnlyManager("not-jdbc");

        manager.execute((resource, status) -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                status.putAttributeIfAbsent(Connection.class, connection);
                insert(connection, 42);

                try (Connection got = proxy.getConnection();
                     Statement statement = got.createStatement();
                     ResultSet resultSet = statement.executeQuery("select id from entries")) {
                    assertTrue(resultSet.next(), "the proxy must reuse the attribute connection's uncommitted work");
                    assertEquals(42, resultSet.getInt(1));
                }
                connection.rollback();
            }
            return null;
        });
    }

    @Test
    void joinsTheTransactionAndCommitsWithIt() throws Exception {
        DataSource dataSource = newDataSource("proxy4");
        createTable(dataSource);
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
        ResourceOnlyManager manager = new ResourceOnlyManager("not-jdbc");

        manager.execute((resource, status) -> {
            Connection joined = proxy.getConnection();
            assertFalse(joined.getAutoCommit(), "a joined connection must run in transaction mode");
            insert(joined, 7);
            joined.close();
            assertFalse(joined.isClosed(), "close is suppressed while the transaction runs");
            assertNotNull(status.getAttribute(Connection.class), "a joined connection is bound to the transaction");
            return null;
        });

        assertEquals(1, count(dataSource),
                "the joined connection commits when the transaction commits");
    }

    @Test
    void joinsTheTransactionAndRollsBackWithIt() throws Exception {
        DataSource dataSource = newDataSource("proxy5");
        createTable(dataSource);
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
        ResourceOnlyManager manager = new ResourceOnlyManager("not-jdbc");

        try {
            manager.execute((resource, status) -> {
                insert(proxy.getConnection(), 8);
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the transaction rolled back
        }

        assertEquals(0, count(dataSource), "the joined connection rolls back when the transaction does");
    }

    @Test
    void completedStatus_doesNotHandOutItsConnection() throws Exception {
        DataSource dataSource = newDataSource("proxy6");
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        DataSource proxy = manager.getTransactionAwareDataSource();
        AtomicReference<TransactionStatus<Connection>> completed = new AtomicReference<>();

        manager.execute((connection, status) -> {
            completed.set(status);
            return null;
        });

        Connection released = completed.get().getResource();
        assertNotNull(released);
        assertTrue(released.isClosed());

        TransactionStatusHolder.call(completed.get(), () -> {
            try (Connection fresh = proxy.getConnection()) {
                assertNotSame(released, fresh);
                assertFalse(fresh.isClosed(), "a completed transaction must not hand out its closed connection");
            }
            Connection fresh = proxy.getConnection();
            fresh.close();
            assertTrue(fresh.isClosed(), "outside an active transaction close must be real");
            return null;
        });
    }

    @Test
    void passesCredentialsToTheDelegateWhenNoTransactionIsActive() throws Exception {
        DataSource dataSource = newDataSource("proxy7");
        List<String> requested = new ArrayList<>();
        DataSource recording = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && args != null) {
                        requested.add(args[0] + ":" + args[1]);
                    }
                    try {
                        return method.invoke(dataSource, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        DataSource proxy = new TransactionAwareDataSourceProxy(recording);

        try (Connection connection = proxy.getConnection("app", "secret")) {
            assertNotNull(connection);
        }

        assertEquals(List.of("app:secret"), requested,
                "outside a transaction the pool, not the proxy, decides the credentials");
    }

    // --- helpers -------------------------------------------------------------

    private static DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists entries (id int primary key)");
        }
    }

    private static void insert(Connection connection, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("insert into entries (id) values (" + id + ")");
        }
    }

    private static int count(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from entries")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * A manager that binds a non-JDBC resource, standing in for a JPA manager: the proxy must
     * fall back to the {@code Connection.class} attribute or join the transaction itself.
     */
    private static final class ResourceOnlyManager extends AbstractTransactionOperations<String> {

        private final String resource;

        ResourceOnlyManager(String resource) {
            this.resource = resource;
        }

        @Override
        protected void doBegin(DefaultTransactionStatus<String> status) {
            bindResource(status, resource);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus<String> status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus<String> status) {
        }
    }
}
