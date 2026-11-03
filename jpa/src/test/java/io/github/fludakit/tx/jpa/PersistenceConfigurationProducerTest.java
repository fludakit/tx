package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.RawDataSource;
import io.github.fludakit.tx.it.jpa.TestEntity;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.PersistenceConfiguration;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PersistenceConfigurationProducer}: the pool it binds, the persistence unit
 * name it derives, and the customizers it applies.
 */
class PersistenceConfigurationProducerTest {

    @Test
    void bindsTheRawPoolAndDefaultsTheUnitName() {
        try (WeldContainer container = container(RawAndGeneralConfig.class, EntityCustomizer.class)) {
            RawAndGeneralConfig config = container.select(RawAndGeneralConfig.class).get();
            PersistenceConfiguration configuration = container.select(PersistenceConfiguration.class).get();

            assertSame(config.pool,
                    configuration.properties().get(PersistenceConfiguration.JDBC_DATASOURCE),
                    "the Jakarta persistence property must carry the pool");
            assertSame(config.pool, configuration.properties().get(HIBERNATE_DATA_SOURCE),
                    "the Hibernate connection property must carry the pool");
            assertEquals(PersistenceConfigurationProducer.DEFAULT_PERSISTENCE_UNIT_NAME, configuration.name());
        }
    }

    @Test
    void appliesTheCustomizer() {
        try (WeldContainer container = container(RawAndGeneralConfig.class, EntityCustomizer.class)) {
            PersistenceConfiguration configuration = container.select(PersistenceConfiguration.class).get();

            assertEquals(List.of(TestEntity.class), configuration.managedClasses());
            assertEquals("create", configuration.properties().get("hibernate.hbm2ddl.auto"));
        }
    }

    @Test
    void usesTheNameACustomizerSupplies() {
        try (WeldContainer container = container(DefaultOnlyConfig.class, NamedCustomizer.class)) {
            PersistenceConfiguration configuration = container.select(PersistenceConfiguration.class).get();

            assertEquals("customPU", configuration.name());
            assertTrue(configuration.managedClasses().isEmpty(),
                    "a naming customizer that customizes nothing adds nothing");
        }
    }

    @Test
    void rejectsConflictingUnitNames() {
        try (WeldContainer container = container(DefaultOnlyConfig.class, NamedCustomizer.class,
                OtherNamedCustomizer.class)) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> container.select(PersistenceConfiguration.class).get());

            assertTrue(messages(failure).contains("Conflicting persistence unit names"),
                    () -> "unexpected failure: " + messages(failure));
        }
    }

    @Test
    void fallsBackToTheDefaultDataSourceWhenNoRawBeanExists() {
        try (WeldContainer container = container(DefaultOnlyConfig.class)) {
            DefaultOnlyConfig config = container.select(DefaultOnlyConfig.class).get();
            PersistenceConfiguration configuration = container.select(PersistenceConfiguration.class).get();

            assertSame(config.pool, configuration.properties().get(PersistenceConfiguration.JDBC_DATASOURCE));
            assertEquals(PersistenceConfigurationProducer.DEFAULT_PERSISTENCE_UNIT_NAME, configuration.name());
        }
    }

    @Test
    void rejectsTheTransactionAwareProxyAsTheDefaultDataSource() {
        try (WeldContainer container = container(ProxyOnlyConfig.class)) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> container.select(PersistenceConfiguration.class).get());

            assertTrue(messages(failure).contains("TransactionAwareDataSourceProxy"),
                    () -> "unexpected failure: " + messages(failure));
        }
    }

    @Test
    void failsWhenNoDataSourceBeanExists() {
        try (WeldContainer container = container(NoDataSourceConfig.class)) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> container.select(PersistenceConfiguration.class).get());

            assertTrue(messages(failure).contains("No DataSource bean is available"),
                    () -> "unexpected failure: " + messages(failure));
        }
    }

    // --- fixtures ------------------------------------------------------------

    private static final String HIBERNATE_DATA_SOURCE = "hibernate.connection.datasource";

    private static WeldContainer container(Class<?>... beans) {
        Weld weld = new Weld().disableDiscovery().addBeanClass(PersistenceConfigurationProducer.class);
        for (Class<?> bean : beans) {
            weld.addBeanClass(bean);
        }
        return weld.initialize();
    }

    private static String messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return String.join(" | ", messages);
    }

    private static HikariDataSource pool(String database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }

    @Singleton
    public static class RawAndGeneralConfig {

        final HikariDataSource pool = pool("producer-raw");
        final DataSource proxy = new TransactionAwareDataSourceProxy(pool);

        @Produces
        @RawDataSource
        @Singleton
        public DataSource rawPool() {
            return pool;
        }

        @Produces
        @Singleton
        public DataSource dataSource() {
            return proxy;
        }

        @PreDestroy
        void close() {
            pool.close();
        }
    }

    @Singleton
    public static class DefaultOnlyConfig {

        final HikariDataSource pool = pool("producer-default");

        @Produces
        @Singleton
        public DataSource dataSource() {
            return pool;
        }

        @PreDestroy
        void close() {
            pool.close();
        }
    }

    @Singleton
    public static class ProxyOnlyConfig {

        final HikariDataSource pool = pool("producer-proxy");

        @Produces
        @Singleton
        public DataSource dataSource() {
            return new TransactionAwareDataSourceProxy(pool);
        }

        @PreDestroy
        void close() {
            pool.close();
        }
    }

    @Singleton
    public static class NoDataSourceConfig {
    }

    @ApplicationScoped
    public static class EntityCustomizer implements PersistenceConfigurationCustomizer {

        @Override
        public void customize(PersistenceConfiguration configuration) {
            configuration.managedClass(TestEntity.class)
                    .property("hibernate.hbm2ddl.auto", "create");
        }
    }

    @ApplicationScoped
    public static class NamedCustomizer implements PersistenceConfigurationCustomizer {

        @Override
        public String persistenceUnitName() {
            return "customPU";
        }

        @Override
        public void customize(PersistenceConfiguration configuration) {
            // naming only
        }
    }

    @ApplicationScoped
    public static class OtherNamedCustomizer implements PersistenceConfigurationCustomizer {

        @Override
        public String persistenceUnitName() {
            return "otherPU";
        }

        @Override
        public void customize(PersistenceConfiguration configuration) {
            // naming only
        }
    }
}
