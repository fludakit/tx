package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionCallback;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

        Connection bound = context.getResource(dataSource, Connection.class);
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

        Connection bound = context.getResource(dataSource, Connection.class);
        bound.close();
    }

    @Test
    void afterCompletionResetsAndClosesConnection() throws Exception {
        DataSource dataSource = newDataSource("dsmgr3");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);

        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        Connection bound = context.getResource(dataSource, Connection.class);

        context.getCallbacks()
                .forEach(s -> s.afterCompletion(TransactionCallback.CompletionStatus.COMMITTED));

        assertTrue(bound.isClosed());
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
