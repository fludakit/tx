package io.github.fludakit.tx.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manager only borrows the {@link EntityManagerFactory}: building it from a persistence unit
 * plus the application pool stays with the caller — or, in CDI, with
 * {@link EntityManagerFactoryProducer} — and so does closing it.
 */
class JpaTransactionManagerBootstrapTest {

    private static final Map<String, Object> PROPERTIES = Map.of("hibernate.hbm2ddl.auto",
            "create",
            "hibernate.dialect",
            "org.hibernate.dialect.H2Dialect"
    );

    @Test
    void usesTheCallerEntityManagerFactoryAndLeavesItOpen() throws Exception {
        try (HikariDataSource pool = pool("jpa-bootstrap");
             EntityManagerFactory emf = JpaBootstrap.create("test", pool, PROPERTIES)) {
            JpaTransactionManager manager = new JpaTransactionManager(emf);

            assertSame(emf, manager.getEntityManagerFactory());

            manager.execute((entityManager, status) -> {
                entityManager.persist(new JpaTransactionManagerTest.TestItem(1L, "Bootstrapped"));
                return null;
            });

            assertEquals(List.of("Bootstrapped"), names(pool, 1));
            assertTrue(emf.isOpen(), "a factory the manager did not create stays the caller's");
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
             ResultSet resultSet = statement.executeQuery("SELECT name FROM test_items WHERE id = " + id)) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }
}
