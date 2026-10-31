package io.github.fludakit.tx.support;

import io.github.fludakit.tx.JdbcSavepoint;
import io.github.fludakit.tx.NestedTransactionNotSupportedException;
import io.github.fludakit.tx.TransactionSavepoint;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Savepoint behaviour shared by the JDBC and JPA managers: names, the lazily resolved connection,
 * and — the reason this class exists — a driver that rejects {@code releaseSavepoint}.
 */
class JdbcSavepointManagerTest {

    @Test
    void createsRollsBackAndReleasesOnTheBoundConnection() {
        StubConnection connection = new StubConnection(null);
        JdbcSavepointManager manager = JdbcSavepointManager.over(connection::proxy);

        TransactionSavepoint savepoint = manager.createSavepoint();
        manager.rollbackToSavepoint(savepoint);
        manager.releaseSavepoint(savepoint);

        assertEquals(List.of("setSavepoint:fluda_sp_1", "rollback", "releaseSavepoint"), connection.calls());
    }

    @Test
    void generatesDistinctNames() {
        StubConnection connection = new StubConnection(null);
        JdbcSavepointManager manager = JdbcSavepointManager.over(connection::proxy);

        manager.createSavepoint();
        manager.createSavepoint();

        assertEquals(List.of("setSavepoint:fluda_sp_1", "setSavepoint:fluda_sp_2"), connection.calls());
    }

    @Test
    void toleratesADriverThatDoesNotSupportReleasingSavepoints() {
        StubConnection connection = new StubConnection(new SQLFeatureNotSupportedException("not supported"));
        JdbcSavepointManager manager = JdbcSavepointManager.over(connection::proxy);

        manager.releaseSavepoint(new JdbcSavepoint(newSavepoint(), "fluda_sp_1"));

        assertEquals(List.of("releaseSavepoint"), connection.calls());
    }

    @Test
    void toleratesTheMessageSomeDriversUseForAnUnsupportedRelease() {
        StubConnection connection = new StubConnection(
                new SQLException("This operation is not supported."));
        JdbcSavepointManager manager = JdbcSavepointManager.over(connection::proxy);

        manager.releaseSavepoint(new JdbcSavepoint(newSavepoint(), "fluda_sp_1"));

        assertEquals(List.of("releaseSavepoint"), connection.calls());
    }

    @Test
    void failsOnARealReleaseFailure() {
        StubConnection connection = new StubConnection(new SQLException("connection is gone"));
        JdbcSavepointManager manager = JdbcSavepointManager.over(connection::proxy);
        TransactionSavepoint savepoint = new JdbcSavepoint(newSavepoint(), "fluda_sp_1");

        assertThrows(io.github.fludakit.tx.TransactionSystemException.class,
                () -> manager.releaseSavepoint(savepoint));
    }

    @Test
    void savepointsAreUnavailableWithoutABoundConnection() {
        JdbcSavepointManager manager = JdbcSavepointManager.over(() -> null);

        assertThrows(NestedTransactionNotSupportedException.class, manager::createSavepoint);
    }

    // --- helpers -------------------------------------------------------------

    private static Savepoint newSavepoint() {
        return (Savepoint) Proxy.newProxyInstance(
                Savepoint.class.getClassLoader(),
                new Class<?>[]{Savepoint.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSavepointId" -> 1;
                    case "getSavepointName" -> "fluda_sp_1";
                    default -> null;
                });
    }

    /**
     * A {@link Connection} proxy that records savepoint calls and optionally fails releases with
     * the given exception.
     */
    private static final class StubConnection implements InvocationHandler {

        private final SQLException releaseFailure;
        private final List<String> calls = new ArrayList<>();

        StubConnection(SQLException releaseFailure) {
            this.releaseFailure = releaseFailure;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "setSavepoint" -> {
                    calls.add("setSavepoint:" + args[0]);
                    yield newSavepoint();
                }
                case "rollback" -> {
                    calls.add("rollback");
                    yield null;
                }
                case "releaseSavepoint" -> {
                    calls.add("releaseSavepoint");
                    if (releaseFailure != null) {
                        throw releaseFailure;
                    }
                    yield null;
                }
                case "isClosed" -> false;
                default -> null;
            };
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    this);
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }
}
