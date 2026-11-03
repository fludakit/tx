package io.github.fludakit.tx.it.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.fludakit.tx.RawDataSource;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;
import io.github.fludakit.tx.jpa.PersistenceConfigurationCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.PersistenceConfiguration;

import javax.sql.DataSource;

/**
 * CDI/DB wiring for the JPA integration tests: a raw pool for the transaction manager and the
 * persistence unit, a transaction-aware proxy as the default {@code DataSource} for plain JDBC
 * code, and the customizer that turns the library's {@link PersistenceConfiguration} into the
 * test unit.
 */
@ApplicationScoped
public class JpaTestConfig implements PersistenceConfigurationCustomizer {

    private final HikariDataSource rawPool;

    public JpaTestConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:jpacdi;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        this.rawPool = new HikariDataSource(config);
    }

    @Produces
    @RawDataSource
    @ApplicationScoped
    public DataSource rawPool() {
        return rawPool;
    }

    @Produces
    @ApplicationScoped
    public DataSource dataSource() {
        return new TransactionAwareDataSourceProxy(rawPool);
    }

    @Override
    public void customize(PersistenceConfiguration configuration) {
        configuration.managedClass(TestEntity.class)
                .property("hibernate.hbm2ddl.auto", "create")
                .property("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    }
}
