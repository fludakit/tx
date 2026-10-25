package io.github.fludakit.tx.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.fludakit.tx.jpa.JpaBootstrap;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionSystemException;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionCallback;
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

        emf = JpaBootstrap.create("test", dataSource, Map.of(
                "hibernate.hbm2ddl.auto", "create",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
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
        assertInstanceOf(EntityManager.class, context.getResource(EntityManager.class));
        assertInstanceOf(Connection.class, context.getResource(Connection.class));

        EntityManager em = context.getResource(EntityManager.class);
        assertTrue(em.isOpen());
        assertTrue(em.getTransaction().isActive());

        cleanup(context);
    }

    @Test
    void commit_flushesAndPersists() throws Exception {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager em = context.getResource(EntityManager.class);

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
        EntityManager em = context.getResource(EntityManager.class);

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
        EntityManager em = context.getResource(EntityManager.class);
        assertTrue(em.isOpen());

        List<TransactionCallback> syncs = context.getCallbacks();
        assertFalse(syncs.isEmpty());
        syncs.getFirst().afterCompletion(TransactionCallback.CompletionStatus.COMMITTED);

        assertFalse(em.isOpen());
    }

    @Test
    void currentEntityManager_returnsBoundEm() {
        TransactionContext context = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager expected = context.getResource(EntityManager.class);

        try {
            io.github.fludakit.tx.support.TransactionContextHolder.call(context, () -> {
                EntityManager actual = JpaHelper.currentEntityManager();
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
                () -> JpaHelper.currentEntityManager());
    }

    private void cleanup(TransactionContext context) {
        try {
            EntityManager em = context.getResource(EntityManager.class);
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
        } catch (Exception ignored) {
        }
        context.getCallbacks().forEach(
                s -> s.afterCompletion(TransactionCallback.CompletionStatus.ROLLED_BACK));
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

    @Test
    void getTransaction_extractorFailure_rollsBackClosesAndThrows() {
        // the extractor throws IllegalStateException - not the PersistenceException the old
        // code caught - so the begun EntityTransaction and EntityManager used to leak
        java.util.concurrent.atomic.AtomicReference<EntityManager> created =
                new java.util.concurrent.atomic.AtomicReference<>();
        ConnectionExtractor throwing = em -> {
            created.set(em);
            throw new IllegalStateException("no connection for you");
        };
        JpaTransactionManager failing = new JpaTransactionManager(emf, dataSource, throwing);

        assertThrows(TransactionSystemException.class, () ->
                failing.getTransaction(TransactionDefinition.DEFAULT));

        assertTrue(created.get() != null);
        assertFalse(created.get().isOpen(), "the failed transaction's EntityManager must be closed");
    }

    @Test
    void customConnectionExtractor_isHonored() throws Exception {
        Connection custom = dataSource.getConnection();
        ConnectionExtractor customExtractor = em -> custom;
        JpaTransactionManager customizing = new JpaTransactionManager(emf, dataSource, customExtractor);

        TransactionContext context = customizing.getTransaction(TransactionDefinition.DEFAULT);
        assertSame(custom, context.getResource(Connection.class));

        context.getCallbacks().forEach(
                s -> s.afterCompletion(TransactionCallback.CompletionStatus.ROLLED_BACK));
        custom.close();
    }

    @Test
    void defaultExtractor_extractsFromHibernateEntityManager() {
        EntityManager em = emf.createEntityManager();
        try {
            Connection extracted = ConnectionExtractor.DEFAULT.extract(em);
            assertNotNull(extracted, "the DEFAULT extractor must produce a connection for Hibernate");
        } finally {
            em.close();
        }
    }
}
