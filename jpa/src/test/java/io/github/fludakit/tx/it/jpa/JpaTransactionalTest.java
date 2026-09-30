package io.github.fludakit.tx.it.jpa;

import io.github.fludakit.tx.cdi.TransactionalCdiExtension;
import io.github.fludakit.tx.cdi.TransactionalInterceptor;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WeldJunit5Extension.class)
class JpaTransactionalTest {

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            JpaTestConfig.class,
            JpaTestService.class,
            TransactionalInterceptor.class,
            TransactionalCdiExtension.class
    ).build();

    @Inject
    private JpaTestService service;

    @Inject
    private EntityManagerFactory emf;

    @Inject
    private DataSource dataSource;

    @BeforeEach
    void createTable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_items");
            stmt.execute("CREATE TABLE test_items (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        }
    }

    @Test
    void transactionalPersist_commitsOnSuccess() throws Exception {
        service.persistItem(1L, "Alpha");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_items WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("Alpha", rs.getString(1));
        }
    }

    @Test
    void transactionalRollback_onRuntimeException() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> service.persistAndThrow(2L, "Beta"));

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_items WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void mixedJpaAndJdbc_shareSameTransaction() throws Exception {
        service.mixJpaAndJdbc(10L, "JPA", 20L, "JDBC");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs1 = stmt.executeQuery("SELECT name FROM test_items WHERE id = 10");
            assertTrue(rs1.next());
            assertEquals("JPA", rs1.getString(1));

            ResultSet rs2 = stmt.executeQuery("SELECT name FROM test_items WHERE id = 20");
            assertTrue(rs2.next());
            assertEquals("JDBC", rs2.getString(1));
        }
    }
}
