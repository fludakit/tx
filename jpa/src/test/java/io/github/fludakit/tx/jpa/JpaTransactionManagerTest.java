package io.github.fludakit.tx.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionSynchronization;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JpaTransactionManagerTest {

    private static HikariDataSource dataSource;
    private static EntityManagerFactory emf;
    private JpaTransactionManager manager;

    @BeforeAll
    static void createEntityManagerFactory() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:jpaunit;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        dataSource = new HikariDataSource(config);

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.connection.datasource", dataSource);

        emf = Persistence.createEntityManagerFactory("test", props);
    }

    @AfterAll
    static void closeResources() {
        if (emf != null) emf.close();
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setUp() {
        manager = new JpaTransactionManager(emf, dataSource);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_items");
            stmt.execute("CREATE TABLE test_items (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getTransaction_bindsEntityManagerAndConnection() {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);

        assertTrue(context.isActualTransactionActive());
        assertInstanceOf(EntityManager.class, context.getResources().get(emf));
        assertInstanceOf(Connection.class, context.getResources().get(dataSource));

        EntityManager em = (EntityManager) context.getResources().get(emf);
        assertTrue(em.isOpen());
        assertTrue(em.getTransaction().isActive());

        cleanup(context);
    }

    @Test
    void commit_flushesAndPersists() throws Exception {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager em = (EntityManager) context.getResources().get(emf);

        em.persist(new TestItem(1L, "Alpha"));

        manager.commit(context);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_items WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("Alpha", rs.getString(1));
        }
    }

    @Test
    void rollback_revertsChanges() throws Exception {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager em = (EntityManager) context.getResources().get(emf);

        em.persist(new TestItem(2L, "Beta"));

        manager.rollback(context);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_items WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void afterCompletion_closesEntityManager() {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager em = (EntityManager) context.getResources().get(emf);
        assertTrue(em.isOpen());

        List<TransactionSynchronization> syncs = context.getSynchronizations();
        assertFalse(syncs.isEmpty());
        syncs.getFirst().afterCompletion(TransactionSynchronization.CompletionStatus.COMMITTED);

        assertFalse(em.isOpen());
    }

    @Test
    void currentEntityManager_returnsBoundEm() {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager expected = (EntityManager) context.getResources().get(emf);

        try {
            io.github.fludakit.tx.support.TransactionContextHolder.call(context, () -> {
                EntityManager actual = JpaTransactionManager.currentEntityManager(emf);
                assertSame(expected, actual);
                return null;
            });
        } catch (Exception e) {
            fail(e);
        } finally {
            cleanup(context);
        }
    }

    @Test
    void currentEntityManager_throwsWhenNoBinding() {
        assertThrows(IllegalStateException.class,
                () -> JpaTransactionManager.currentEntityManager(emf));
    }

    private void cleanup(TransactionContext context) {
        try {
            EntityManager em = (EntityManager) context.getResources().get(emf);
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
        } catch (Exception ignored) {
        }
        context.getSynchronizations().forEach(
                s -> s.afterCompletion(TransactionSynchronization.CompletionStatus.ROLLED_BACK));
    }

    @Entity
    @Table(name = "test_items")
    public static class TestItem {
        @Id
        private Long id;
        private String name;

        public TestItem() {}

        public TestItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
    }
}
