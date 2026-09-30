package io.github.fludakit.tx.it.scoped;

import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.cdi.TransactionalCdiExtension;
import io.github.fludakit.tx.cdi.TransactionalInterceptor;
import io.github.fludakit.tx.jdbc.DataSourceTransactionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionScoped;
import jakarta.transaction.Transactional;
import org.h2.jdbcx.JdbcDataSource;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests @TransactionScoped bean lifecycle.
 */
@ExtendWith(WeldJunit5Extension.class)
class TransactionScopedTest {

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            TestConfig.class,
            TransactionScopedService.class,
            TransactionScopedBean.class,
            TransactionalInterceptor.class,
            TransactionalCdiExtension.class
    ).build();

    @Inject
    private TransactionScopedService service;

    @Test
    void transactionScoped_sameInstanceWithinTransaction() {
        // Both calls happen within the same @Transactional method
        String[] ids = service.getTwoInstanceIds();

        // Same transaction, same instance
        assertEquals(ids[0], ids[1]);
    }

    @Test
    void transactionScoped_differentInstancePerTransaction() {
        String id1 = service.getInstanceId();
        String id2 = service.getInstanceIdInNewTransaction();

        // Different transactions, different instances
        assertNotEquals(id1, id2);
    }

    @Test
    void transactionScoped_statePreservedWithinTransaction() {
        // Set and get within the same @Transactional method
        String value = service.setValueAndGetValue("test-value");

        assertEquals("test-value", value);
    }

    @Test
    void transactionScoped_stateClearedAfterTransaction() {
        // Set value in first transaction
        service.setValue("first-tx");
        
        // Verify it was set (within same transaction)
        String firstValue = service.setValueAndGetValue("first-tx");
        assertEquals("first-tx", firstValue);

        // New transaction - state should be fresh (null)
        String secondValue = service.getValueInNewTransaction();
        assertNull(secondValue);
    }

    @ApplicationScoped
    public static class TestConfig {

        @Produces
        @ApplicationScoped
        public DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:txscoped;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }

        @Produces
        @ApplicationScoped
        public PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }

    @ApplicationScoped
    public static class TransactionScopedService {

        @Inject
        private TransactionScopedBean bean;

        @Transactional
        public String[] getTwoInstanceIds() {
            String id1 = bean.getId();
            String id2 = bean.getId();
            return new String[] { id1, id2 };
        }

        @Transactional
        public String getInstanceId() {
            return bean.getId();
        }

        @Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
        public String getInstanceIdInNewTransaction() {
            return bean.getId();
        }

        @Transactional
        public void setValue(String value) {
            bean.setValue(value);
        }

        @Transactional
        public String getValue() {
            return bean.getValue();
        }

        @Transactional
        public String setValueAndGetValue(String value) {
            bean.setValue(value);
            return bean.getValue();
        }

        @Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
        public String getValueInNewTransaction() {
            return bean.getValue();
        }
    }

    @TransactionScoped
    public static class TransactionScopedBean implements java.io.Serializable {

        private static final long serialVersionUID = 1L;

        private final String id = java.util.UUID.randomUUID().toString();
        private String value;

        public String getId() {
            return id;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
