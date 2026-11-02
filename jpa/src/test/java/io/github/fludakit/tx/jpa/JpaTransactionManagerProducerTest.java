package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.RawDataSource;
import io.github.fludakit.tx.it.jpa.TestEntity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceConfiguration;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link JpaTransactionManagerProducer}: the connection extractor the produced manager
 * uses, from the application when it declares one and {@link ConnectionExtractor#DEFAULT}
 * otherwise.
 */
class JpaTransactionManagerProducerTest {

    @Test
    void fallsBackToTheDefaultExtractorWhenNoBeanExists() throws Exception {
        try (WeldContainer container = container(Config.class)) {
            JpaTransactionManager manager = container.select(JpaTransactionManager.class).get();

            manager.execute((entityManager, status) -> {
                assertInstanceOf(Connection.class, status.getAttribute(Connection.class),
                        "the default extractor must reach Hibernate's transactional connection");
                return null;
            });
        }
    }

    @Test
    void usesTheApplicationExtractorBean() throws Exception {
        try (WeldContainer container = container(Config.class, CustomExtractor.class)) {
            JpaTransactionManager manager = container.select(JpaTransactionManager.class).get();
            CustomExtractor extractor = container.select(CustomExtractor.class).get();

            try {
                manager.execute((entityManager, status) -> {
                    assertSame(extractor.extracted.get(), status.getAttribute(Connection.class),
                            "the application's extractor must be the one the manager calls");
                    return null;
                });

                assertNotNull(extractor.extracted.get(), "the application's extractor must run");
            } finally {
                Connection connection = extractor.extracted.get();
                if (connection != null) {
                    connection.close();
                }
            }
        }
    }

    // --- fixtures ------------------------------------------------------------

    private static WeldContainer container(Class<?>... beans) {
        Weld weld = new Weld()
                .disableDiscovery()
                .addBeanClasses(PersistenceConfigurationProducer.class,
                        EntityManagerFactoryProducer.class,
                        JpaTransactionManagerProducer.class,
                        Config.class);
        for (Class<?> bean : beans) {
            weld.addBeanClass(bean);
        }
        return weld.initialize();
    }

    private static HikariDataSource pool(String database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }

    @Singleton
    public static class Config implements PersistenceConfigurationCustomizer {

        final HikariDataSource pool = pool("manager-producer");

        @Produces
        @RawDataSource
        @Singleton
        public DataSource rawPool() {
            return pool;
        }

        @Override
        public void customize(PersistenceConfiguration configuration) {
            configuration.managedClass(TestEntity.class)
                    .property("hibernate.hbm2ddl.auto", "create")
                    .property("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        }

        @PreDestroy
        void close() {
            pool.close();
        }
    }

    /**
     * Returns a connection of its own: the assertion proves the manager called this bean rather
     * than {@link ConnectionExtractor#DEFAULT}. Committing the transaction does not depend on it —
     * the provider commits its own connection.
     */
    @Singleton
    public static class CustomExtractor implements ConnectionExtractor {

        final AtomicReference<Connection> extracted = new AtomicReference<>();

        @Inject
        Config config;

        @Override
        public Connection extract(EntityManager em) {
            try {
                Connection connection = config.pool.getConnection();
                extracted.set(connection);
                return connection;
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not borrow a connection", ex);
            }
        }
    }
}
