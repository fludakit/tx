package io.github.fludakit.tx.it.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;
import io.github.fludakit.tx.jpa.JpaTransactionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Named;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import javax.sql.DataSource;
import java.util.HashMap;
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

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.connection.datasource", rawPool);
        this.emf = Persistence.createEntityManagerFactory("test", props);
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
    public PlatformTransactionManager transactionManager() {
        return new JpaTransactionManager(emf, rawPool);
    }
}
