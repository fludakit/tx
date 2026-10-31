package io.github.fludakit.tx.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preferred construction: a persistence unit name plus the application's pool. The manager
 * creates the {@link EntityManagerFactory} over that exact {@link javax.sql.DataSource} and owns
 * it; a factory the caller passes in stays the caller's.
 */
class JpaTransactionManagerBootstrapTest {

    private static final Map<String, Object> PROPERTIES = Map.of(
            "hibernate.hbm2ddl.auto", "create",
            "hibernate.dialect", "org.hibernate.dialect.H2Dialect");

    @Test
    void createsTheEntityManagerFactoryFromTheDataSourceAndOwnsIt() throws Exception {
        HikariDataSource pool = pool("jpa-bootstrap");
        JpaTransactionManager manager = new JpaTransactionManager("test", pool, PROPERTIES);
        try {
            assertNotNull(manager.getEntityManagerFactory());

            manager.execute((entityManager, status) -> {
                entityManager.persist(new JpaTransactionManagerTest.TestItem(1L, "Bootstrapped"));
                return null;
            });

            assertEquals(List.of("Bootstrapped"), names(pool, 1));
        } finally {
            manager.close();
            assertFalse(manager.getEntityManagerFactory().isOpen(),
                    "a factory the manager created must be closed with it");
            pool.close();
        }
    }

    @Test
    void leavesACallerOwnedEntityManagerFactoryOpen() {
        HikariDataSource pool = pool("jpa-caller-owned");
        EntityManagerFactory entityManagerFactory = JpaBootstrap.create("test", pool, PROPERTIES);
        try {
            JpaTransactionManager manager = new JpaTransactionManager(entityManagerFactory);
            manager.close();

            assertTrue(entityManagerFactory.isOpen(),
                    "a factory the caller created belongs to the caller");
        } finally {
            entityManagerFactory.close();
            pool.close();
        }
    }

    // --- helpers -------------------------------------------------------------

    private static HikariDataSource pool(String database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }

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
}
