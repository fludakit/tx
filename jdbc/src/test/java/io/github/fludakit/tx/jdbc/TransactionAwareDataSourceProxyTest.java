package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.support.TransactionCallback;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void closeIsSuppressedInsideTransaction() throws Exception {
        DataSource dataSource = newDataSource("proxy2");
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
        Connection txConnection = dataSource.getConnection();

        TransactionContextHolder.call(new TransactionContext(true), () -> {
            TransactionContextHolder.get().bindResource(Connection.class, txConnection);
            Connection got = proxy.getConnection();
            got.close();
            assertFalse(txConnection.isClosed(), "close must be suppressed inside a transaction");
            return null;
        });

        txConnection.close();
    }

    @Test
    void joinsTransactionWhenNotYetBound() throws Exception {
        DataSource dataSource = newDataSource("proxy3");
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);

        TransactionContextHolder.call(new TransactionContext(true), () -> {
            proxy.getConnection();

            Connection bound = TransactionContextHolder.get().getResource(Connection.class);
            assertNotNull(bound, "a connection must be bound on join");
            assertFalse(bound.getAutoCommit());
            bound.close();
            return null;
        });
    }

    @Test
    void completedContextOnThread_doesNotHandOutItsConnection() throws Exception {
        DataSource dataSource = newDataSource("proxy4");
        DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
        Connection closedTxConnection = dataSource.getConnection();
        closedTxConnection.close();

        TransactionContext completed = new TransactionContext(true);
        completed.bindResource(Connection.class, closedTxConnection);
        completed.markCompleted(TransactionCallback.CompletionStatus.COMMITTED);
        // simulate a leftover binding on a pooled thread: the context stays bound after completion
        TransactionContextHolder.call(completed, () -> {
            Connection fresh = proxy.getConnection();
            assertFalse(fresh.isClosed(), "a completed context must not hand out its closed connection");
            assertNotSame(closedTxConnection, fresh);
            fresh.close();
            assertTrue(fresh.isClosed(), "outside an active transaction close must be real");
            return null;
        });
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
