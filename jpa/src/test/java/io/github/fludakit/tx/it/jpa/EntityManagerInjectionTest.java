package io.github.fludakit.tx.it.jpa;

import io.github.fludakit.tx.cdi.TransactionalCdiExtension;
import io.github.fludakit.tx.cdi.TransactionalInterceptor;
import io.github.fludakit.tx.jpa.EntityManagerProducer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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

/**
 * Tests CDI injection of {@link EntityManager} via {@link EntityManagerProducer}.
 */
@ExtendWith(WeldJunit5Extension.class)
class EntityManagerInjectionTest {

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            JpaTestConfig.class,
            InjectedEntityManagerService.class,
            EntityManagerProducer.class,
            TransactionalInterceptor.class,
            TransactionalCdiExtension.class
    ).build();

    @Inject
    private InjectedEntityManagerService service;

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
    void injectedEntityManager_persistsEntity() throws Exception {
        service.persistWithInjectedEm(1L, "Injected");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_items WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("Injected", rs.getString(1));
        }
    }

    @Test
    void injectedEntityManager_rollsBackOnException() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> service.persistAndThrowWithInjectedEm(2L, "Rollback"));

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_items WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @ApplicationScoped
    public static class InjectedEntityManagerService {

        @Inject
        EntityManager em;  // Direct injection, no static method call

        @Transactional
        public void persistWithInjectedEm(long id, String name) {
            em.persist(new TestEntity(id, name));
        }

        @Transactional
        public void persistAndThrowWithInjectedEm(long id, String name) {
            em.persist(new TestEntity(id, name));
            throw new IllegalStateException("rollback");
        }
    }
}
