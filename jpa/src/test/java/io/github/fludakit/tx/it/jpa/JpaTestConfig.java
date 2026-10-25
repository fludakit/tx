package io.github.fludakit.tx.it.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;
import io.github.fludakit.tx.jpa.JpaBootstrap;
import io.github.fludakit.tx.jpa.JpaTransactionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import javax.sql.DataSource;
import java.util.Map;

@ApplicationScoped
public class JpaTestConfig {

    private final HikariDataSource rawPool;
    private final EntityManagerFactory emf;

    public JpaTestConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:jpacdi;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        this.rawPool = new HikariDataSource(config);

        this.emf = JpaBootstrap.create("test", rawPool, Map.of(
                "hibernate.hbm2ddl.auto", "create",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
    }

    @Produces
    @Named("raw")
    @Typed(HikariDataSource.class)
    @ApplicationScoped
    public HikariDataSource rawPool() {
        return rawPool;
    }

    @Produces
    @ApplicationScoped
    public DataSource dataSource() {
        return new TransactionAwareDataSourceProxy(rawPool);
    }

    @Produces
    @ApplicationScoped
    public EntityManagerFactory entityManagerFactory() {
        return emf;
    }

    @Produces
    @ApplicationScoped
    public TransactionManager<EntityManager> transactionManager() {
        return new JpaTransactionManager(emf, rawPool);
    }
}
