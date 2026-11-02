package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.RawDataSource;
import io.github.fludakit.tx.it.jpa.TestEntity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EntityManagerFactoryProducer}: one factory per container, shared by the
 * transaction manager, and closed when the container shuts down.
 */
class EntityManagerFactoryProducerTest {

    @Test
    void producesOneFactorySharedWithTheTransactionManager() {
        try (WeldContainer container = container()) {
            EntityManagerFactory entityManagerFactory = container.select(EntityManagerFactory.class).get();
            JpaTransactionManager manager = container.select(JpaTransactionManager.class).get();

            assertTrue(entityManagerFactory.isOpen());
            assertSame(entityManagerFactory, manager.getEntityManagerFactory());
            assertSame(entityManagerFactory, container.select(EntityManagerFactory.class).get());
        }
    }

    @Test
    void closesTheFactoryWhenTheContainerShutsDown() {
        EntityManagerFactory entityManagerFactory;
        try (WeldContainer container = container()) {
            entityManagerFactory = container.select(EntityManagerFactory.class).get();
            assertTrue(entityManagerFactory.isOpen());
        }

        assertFalse(entityManagerFactory.isOpen(), "the @Disposes method must close the factory");
    }

    // --- fixtures ------------------------------------------------------------

    private static WeldContainer container() {
        return new Weld()
                .disableDiscovery()
                .addBeanClasses(PersistenceConfigurationProducer.class,
                        EntityManagerFactoryProducer.class,
                        JpaTransactionManagerProducer.class,
                        Config.class)
                .initialize();
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

        final HikariDataSource pool = pool("emf-producer");

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
    }
}
