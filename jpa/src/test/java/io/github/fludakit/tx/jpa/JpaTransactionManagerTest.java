package io.github.fludakit.tx.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionStatus;
import io.github.fludakit.tx.TransactionSystemException;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link JpaTransactionManager} over Hibernate and H2.
 *
 * <p>They cover the two bindings a JPA transaction exposes — the {@code EntityManager} resource
 * and the {@code Connection} attribute — plus commit, rollback, cleanup, savepoint nesting, and
 * the extractor failure path.</p>
 */
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
        if (emf != null) {
            emf.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new JpaTransactionManager(emf);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS test_items");
            statement.execute("CREATE TABLE test_items (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        }
    }

    @Test
    void execute_bindsTheEntityManagerAndTheConnection() throws Exception {
        manager.execute((entityManager, status) -> {
            assertSame(entityManager, manager.getResource());
            assertSame(entityManager, status.getResource());
            assertSame(entityManager, JpaHelper.currentEntityManager(),
                    "the transaction's EntityManager is the resource, so lookups find it");
            assertTrue(entityManager.isOpen());
            assertTrue(entityManager.getTransaction().isActive());
            assertTrue(status.isNewTransaction());
            assertInstanceOf(Connection.class, status.getAttribute(Connection.class),
                    "the underlying connection is exposed for JDBC interop");
            return null;
        });

        assertTrue(manager.findTransactionStatus().isEmpty());
    }

    @Test
    void commit_flushesAndPersists() throws Exception {
        manager.execute((entityManager, status) -> {
            entityManager.persist(new TestItem(1L, "Alpha"));
            return null;
        });

        assertEquals(List.of("Alpha"), names(dataSource, 1));
    }

    @Test
    void runtimeFailure_rollsBackAndClosesTheEntityManager() throws Exception {
        AtomicReference<EntityManager> bound = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () ->
                manager.execute((entityManager, status) -> {
                    bound.set(entityManager);
                    entityManager.persist(new TestItem(2L, "Beta"));
                    throw new IllegalStateException("boom");
                }));

        assertEquals(List.of(), names(dataSource, 2));
        assertFalse(bound.get().isOpen(), "completion must close the EntityManager");
    }

    @Test
    void checkedFailure_commitsByDefault() throws Exception {
        assertThrows(CheckedFailure.class, () ->
                manager.execute((entityManager, status) -> {
                    entityManager.persist(new TestItem(3L, "Gamma"));
                    throw new CheckedFailure("checked");
                }));

        assertEquals(List.of("Gamma"), names(dataSource, 3));
    }

    @Test
    void joinedCallSharesTheEntityManagerAndTheBoundaryCommits() throws Exception {
        manager.execute((entityManager, status) -> {
            manager.execute((innerEntityManager, inner) -> {
                assertSame(entityManager, innerEntityManager);
                assertFalse(inner.isNewTransaction());
                innerEntityManager.persist(new TestItem(4L, "Joined"));
                return null;
            });
            return null;
        });

        assertEquals(List.of("Joined"), names(dataSource, 4));
    }

    @Test
    void manualDemarcation_commitPersistsAndClosesTheEntityManager() throws Exception {
        TransactionStatus<EntityManager> status = manager.getTransaction(TransactionDefinition.DEFAULT);
        EntityManager entityManager = status.getResource();
        assertNotNull(entityManager);

        entityManager.persist(new TestItem(5L, "Manual"));
        manager.commit(status);

        assertTrue(status.isCompleted());
        assertFalse(entityManager.isOpen());
        assertEquals(List.of("Manual"), names(dataSource, 5));
    }

    @Test
    void manualDemarcation_rollbackDiscardsTheChanges() throws Exception {
        TransactionStatus<EntityManager> status = manager.getTransaction(TransactionDefinition.DEFAULT);
        status.getResource().persist(new TestItem(6L, "Manual"));
        manager.rollback(status);

        assertFalse(status.getResource().isOpen());
        assertEquals(List.of(), names(dataSource, 6));
    }

    @Test
    void nested_successIsPartOfTheOuterTransaction() throws Exception {
        manager.execute((entityManager, status) -> {
            entityManager.persist(new TestItem(10L, "Outer"));
            manager.executeNested((nestedEntityManager, nested) -> {
                nestedEntityManager.persist(new TestItem(11L, "Nested"));
                return null;
            });
            return null;
        });

        assertEquals(List.of("Outer"), names(dataSource, 10));
        assertEquals(List.of("Nested"), names(dataSource, 11));
    }

    @Test
    void nested_failureRollsBackOnlyTheNestedChanges() throws Exception {
        manager.execute((entityManager, status) -> {
            entityManager.persist(new TestItem(12L, "Outer"));
            entityManager.flush();

            assertThrows(IllegalStateException.class, () ->
                    manager.executeNested((nestedEntityManager, nested) -> {
                        nestedEntityManager.persist(new TestItem(13L, "Nested"));
                        // a JDBC savepoint only reaches rows the provider has already flushed
                        nestedEntityManager.flush();
                        throw new IllegalStateException("nested failure");
                    }));

            return null;
        });

        assertEquals(List.of("Outer"), names(dataSource, 12), "the outer work must survive");
        assertEquals(List.of(), names(dataSource, 13), "the nested row must be gone");
    }

    @Test
    void currentEntityManager_throwsOutsideATransaction() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, JpaHelper::currentEntityManager);
        assertTrue(thrown.getMessage().contains("No transaction"));
    }

    @Test
    void beginFailure_extractorThrowingRollsBackClosesAndThrows() {
        AtomicReference<EntityManager> created = new AtomicReference<>();
        ConnectionExtractor throwing = entityManager -> {
            created.set(entityManager);
            throw new IllegalStateException("no connection for you");
        };
        JpaTransactionManager failing = new JpaTransactionManager(emf, throwing);

        assertThrows(TransactionSystemException.class, () ->
                failing.execute((entityManager, status) -> "never"));

        assertNotNull(created.get());
        assertFalse(created.get().isOpen(), "the failed transaction's EntityManager must be closed");
        assertTrue(failing.findTransactionStatus().isEmpty());
    }

    @Test
    void customConnectionExtractor_isHonored() throws Exception {
        try (Connection custom = dataSource.getConnection()) {
            JpaTransactionManager customizing =
                    new JpaTransactionManager(emf, entityManager -> custom);

            manager = customizing;
            customizing.execute((entityManager, status) -> {
                assertSame(custom, status.getAttribute(Connection.class));
                assertSame(entityManager, status.getResource(),
                        "the EntityManager stays the resource even with a custom extractor");
                return null;
            });
        }
    }

    @Test
    void defaultExtractor_extractsTheTransactionalConnection() throws Exception {
        manager.execute((entityManager, status) -> {
            Connection extracted = status.getAttribute(Connection.class);

            assertNotNull(extracted, "the DEFAULT extractor must produce a connection for Hibernate");
            assertFalse(extracted.getAutoCommit(),
                    "a savepoint on an auto-commit connection would be invalid");
            assertSame(extracted, entityManager.unwrap(org.hibernate.Session.class)
                            .doReturningWork(connection -> connection),
                    "the extractor must hand out the connection the transaction is using, "
                            + "not a second one borrowed from the pool");
            return null;
        });
    }

    // --- helpers -------------------------------------------------------------

    private static List<String> names(HikariDataSource dataSource, long id) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name FROM test_items WHERE id = " + id)) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    @Entity
    @Table(name = "test_items")
    public static class TestItem {

        @Id
        private Long id;
        private String name;

        public TestItem() {
        }

        public TestItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    private static final class CheckedFailure extends Exception {
        CheckedFailure(String message) {
            super(message);
        }
    }
}
