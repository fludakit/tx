package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.RawDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JdbcTransactionManagerProducer}: which {@code DataSource} bean the manager is
 * built over, and what happens when the wiring cannot support a transaction.
 */
class JdbcTransactionManagerProducerTest {

    @Test
    void reusesTheApplicationProxyInsteadOfWrappingThePoolAgain() {
        try (WeldContainer container = container(RawAndGeneralConfig.class)) {
            RawAndGeneralConfig config = container.select(RawAndGeneralConfig.class).get();
            JdbcTransactionManager manager = container.select(JdbcTransactionManager.class).get();

            assertSame(config.proxy, manager.getTransactionAwareDataSource(),
                    "the application's proxy must be reused instead of a second one being created");
            assertSame(config.pool, manager.getDataSource(),
                    "the manager still opens real connections from the pool behind the proxy");
        }
    }

    @Test
    void fallsBackToTheDefaultDataSourceWhenNoRawBeanExists() {
        try (WeldContainer container = container(DefaultOnlyConfig.class)) {
            DefaultOnlyConfig config = container.select(DefaultOnlyConfig.class).get();
            JdbcTransactionManager manager = container.select(JdbcTransactionManager.class).get();

            assertSame(config.pool, manager.getDataSource());
        }
    }

    @Test
    void reusesTheDefaultProxyWhenNoRawBeanExists() {
        try (WeldContainer container = container(ProxyOnlyConfig.class)) {
            ProxyOnlyConfig config = container.select(ProxyOnlyConfig.class).get();
            JdbcTransactionManager manager = container.select(JdbcTransactionManager.class).get();

            assertSame(config.proxy, manager.getTransactionAwareDataSource());
            assertSame(config.pool, manager.getDataSource());
        }
    }

    @Test
    void failsWhenNoDataSourceBeanExists() {
        try (WeldContainer container = container(NoDataSourceConfig.class)) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> container.select(JdbcTransactionManager.class).get());

            assertTrue(messages(failure).contains("No DataSource bean is available"),
                    () -> "unexpected failure: " + messages(failure));
        }
    }

    // --- fixtures ------------------------------------------------------------

    private static WeldContainer container(Class<?> config) {
        return new Weld()
                .disableDiscovery()
                .addBeanClasses(JdbcTransactionManagerProducer.class, config)
                .initialize();
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
        final DataSource proxy = new TransactionAwareDataSourceProxy(pool);

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
    public static class NoDataSourceConfig {
    }
}
