package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSystemException;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link JdbcTransactionManager} against an in-memory H2 database.
 *
 * <p>These exercise the resource half of the contract — connection acquisition, commit/rollback,
 * read-only handling, cleanup, and savepoint nesting — with real SQL, so a green test means the
 * data actually survived (or did not survive) the boundary.</p>
 */
class JdbcTransactionManagerTest {

    @Test
    void execute_beginsCommitsAndReleasesTheConnection() throws Exception {
        DataSource dataSource = newDataSource("mgr1");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        AtomicReference<Connection> bound = new AtomicReference<>();

        String result = manager.execute((connection, status) -> {
            assertSame(connection, manager.getResource());
            assertSame(connection, status.getResource());
            assertFalse(connection.getAutoCommit(), "auto-commit must be off inside the boundary");
            assertTrue(status.isNewTransaction());
            bound.set(connection);
            insert(connection, 1);
            return "done";
        });

        assertEquals("done", result);
        assertNotNull(bound.get());
        assertTrue(bound.get().isClosed(), "completion must release the pooled connection");
        assertEquals(List.of(1), ids(dataSource), "a committed insert must survive the boundary");
    }

    @Test
    void runtimeFailure_rollsBackTheConnection() throws Exception {
        DataSource dataSource = newDataSource("mgr2");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        assertThrows(IllegalStateException.class, () ->
                manager.execute((connection, status) -> {
                    insert(connection, 1);
                    throw new IllegalStateException("boom");
                }));

        assertEquals(List.of(), ids(dataSource), "a rolled back insert must be gone");
    }

    @Test
    void checkedFailure_commitsByDefault() throws Exception {
        DataSource dataSource = newDataSource("mgr3");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        assertThrows(CheckedFailure.class, () ->
                manager.execute((connection, status) -> {
                    insert(connection, 1);
                    throw new CheckedFailure("checked");
                }));

        assertEquals(List.of(1), ids(dataSource), "a checked failure commits unless a rule says otherwise");
    }

    @Test
    void readOnly_boundaryAppliesAndResetsTheHint() throws Exception {
        DataSource dataSource = newDataSource("mgr4");
        createTable(dataSource);
        List<Boolean> hints = new ArrayList<>();
        Connection realConnection = dataSource.getConnection();
        JdbcTransactionManager manager =
                new JdbcTransactionManager(dataSourceReturning(recordingReadOnly(realConnection, hints)));

        manager.executeRead((connection, status) -> {
            assertTrue(status.getDefinition().readOnly());
            return null;
        });

        assertEquals(List.of(true, false), hints,
                "the hint must be applied at begin and cleared before the connection is released");
    }

    @Test
    void joinedBoundaryReusesTheSameConnection() throws Exception {
        DataSource dataSource = newDataSource("mgr5");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        AtomicReference<Connection> outer = new AtomicReference<>();

        manager.execute((connection, status) -> {
            outer.set(connection);
            manager.execute((innerResource, inner) -> {
                assertSame(outer.get(), innerResource, "a participant must share the boundary's connection");
                assertFalse(inner.isNewTransaction());
                insert(innerResource, 1);
                return null;
            });
            return null;
        });

        assertEquals(List.of(1), ids(dataSource));
    }

    @Test
    void manualDemarcation_commitPersistsTheChanges() throws Exception {
        DataSource dataSource = newDataSource("mgr6");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        TransactionStatus<Connection> status = manager.getTransaction(TransactionDefinition.DEFAULT);
        assertTrue(status.isActualTransactionActive());
        assertTrue(status.isNewTransaction());
        assertFalse(status.getResource().getAutoCommit());

        insert(status.getResource(), 1);
        manager.commit(status);

        assertTrue(status.isCompleted());
        assertTrue(status.getResource().isClosed());
        assertEquals(List.of(1), ids(dataSource));
    }

    @Test
    void manualDemarcation_rollbackDiscardsTheChanges() throws Exception {
        DataSource dataSource = newDataSource("mgr7");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        TransactionStatus<Connection> status = manager.getTransaction(TransactionDefinition.DEFAULT);
        insert(status.getResource(), 1);
        manager.rollback(status);

        assertTrue(status.isCompleted());
        assertEquals(List.of(), ids(dataSource));
    }

    @Test
    void nested_successIsPartOfTheOuterTransaction() throws Exception {
        DataSource dataSource = newDataSource("mgr8");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        manager.execute((connection, status) -> {
            insert(connection, 1);
            manager.executeNested((nestedConnection, nested) -> {
                assertSame(connection, nestedConnection);
                assertTrue(nested.isNestedTransaction());
                assertTrue(nested.isSavepointSupported());
                insert(nestedConnection, 2);
                return null;
            });
            return null;
        });

        assertEquals(List.of(1, 2), ids(dataSource), "a released savepoint keeps its changes");
    }

    @Test
    void nested_failureUndoesOnlyTheNestedChanges() throws Exception {
        DataSource dataSource = newDataSource("mgr9");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        manager.execute((connection, status) -> {
            insert(connection, 1);
            assertThrows(IllegalStateException.class, () ->
                    manager.executeNested((nestedConnection, nested) -> {
                        insert(nestedConnection, 2);
                        throw new IllegalStateException("nested failure");
                    }));
            insert(connection, 3);
            return null;
        });

        assertEquals(List.of(1, 3), ids(dataSource), "a rollback to a savepoint must keep the outer work");
    }

    @Test
    void nested_rollbackOnlyRollsBackToTheSavepointAndTheOuterCommits() throws Exception {
        DataSource dataSource = newDataSource("mgr10");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);

        manager.execute((connection, status) -> {
            insert(connection, 1);
            manager.executeNested((nestedConnection, nested) -> {
                insert(nestedConnection, 2);
                nested.setRollbackOnly();
                return null;
            });
            insert(connection, 3);
            return null;
        });

        assertEquals(List.of(1, 3), ids(dataSource));
    }

    @Test
    void completion_resetsTheConnectionBeforeReleasingIt() throws Exception {
        DataSource dataSource = newDataSource("mgr11");
        createTable(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        AtomicReference<Connection> bound = new AtomicReference<>();

        manager.execute((connection, status) -> {
            bound.set(connection);
            return null;
        });

        assertTrue(bound.get().isClosed());
    }

    @Test
    void doBeginFailure_closesTheConnectionAndDoesNotLeaveATransactionBound() throws Exception {
        // setAutoCommit(false) failing means nothing is bound to clean up yet - the manager
        // itself must release the pooled connection before translating
        DataSource dataSource = newDataSource("mgr12");
        AtomicInteger closes = new AtomicInteger();
        Connection pooled = dataSource.getConnection();
        DataSource failing = dataSourceReturning(failingAutoCommit(pooled, closes));
        JdbcTransactionManager manager = new JdbcTransactionManager(failing);

        assertThrows(TransactionSystemException.class, () ->
                manager.execute((connection, status) -> "never"));

        assertEquals(1, closes.get(), "the pooled connection must be closed on the failure path");
        assertTrue(manager.findTransactionStatus().isEmpty(), "nothing may stay ambient after a failed begin");
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

    private static List<Integer> ids(DataSource dataSource) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select id from entries order by id")) {
            while (resultSet.next()) {
                ids.add(resultSet.getInt(1));
            }
        }
        return ids;
    }

    private static DataSource dataSourceReturning(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "getParentLogger" -> Logger.getLogger("stub");
                    case "getLoginTimeout" -> 0;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    /**
     * A real H2 connection whose {@code setAutoCommit(false)} always fails, counting {@code close()}
     * so the test can see whether the manager released it.
     */
    private static Connection failingAutoCommit(Connection delegate, AtomicInteger closes) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("setAutoCommit".equals(method.getName()) && !(boolean) args[0]) {
                        throw new SQLException("simulated setAutoCommit(false) failure");
                    }
                    if ("close".equals(method.getName())) {
                        closes.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * A connection that records every {@code setReadOnly} call: H2 does not report the read-only
     * flag back, so the recorded calls are the only way to see the hint reach the resource.
     */
    private static Connection recordingReadOnly(Connection delegate, List<Boolean> hints) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("setReadOnly".equals(method.getName())) {
                        hints.add((boolean) args[0]);
                        return null;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static final class CheckedFailure extends Exception {
        CheckedFailure(String message) {
            super(message);
        }
    }
}
