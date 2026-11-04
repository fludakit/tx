package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.RawDataSource;

import java.util.logging.Logger;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * CDI producer for a {@link JdbcTransactionManager} over the application's pool.
 *
 * <p>The {@code DataSource} is resolved once, at bean creation: an unqualified
 * {@link TransactionAwareDataSourceProxy} is reused as the manager's view, so application code and
 * the manager share one proxy. Otherwise a {@link DataSource} qualified with
 * {@link RawDataSource} wins over an unqualified pool, and the manager wraps that pool itself.</p>
 *
 * <pre>{@code
 * @Produces
 * @RawDataSource
 * DataSource pool() {
 *     return hikariDataSource;
 * }
 *
 * // the default bean is what plain JDBC code injects
 * @Produces
 * DataSource dataSource() {
 *     return new TransactionAwareDataSourceProxy(pool());
 * }
 * }</pre>
 *
 * <p>With a single pool and no qualifiers at all, the manager builds the transaction-aware view
 * itself, so a plain {@code DataSource} producer is enough.</p>
 *
 * <p>The produced manager has {@link Singleton} scope: it is thread-safe and stateless apart from
 * the pool, so one instance per container is enough, and the concrete
 * {@link JdbcTransactionManager} type stays injectable. The producer class itself is
 * {@link ApplicationScoped} because {@code @Singleton} is not a bean defining annotation:
 * annotated discovery would skip the class, and no producer would be registered at all.</p>
 *
 * @see RawDataSource
 */
@ApplicationScoped
public class JdbcTransactionManagerProducer {

    private static final Logger LOG = Logger.getLogger(JdbcTransactionManagerProducer.class.getName());

    private final Instance<DataSource> rawDataSources;
    private final Instance<DataSource> dataSources;

    /**
     * @param rawDataSources the pools qualified with {@link RawDataSource}, possibly unsatisfied
     * @param dataSources    the default pools, possibly unsatisfied
     */
    @Inject
    JdbcTransactionManagerProducer(@RawDataSource Instance<DataSource> rawDataSources,
                                   Instance<DataSource> dataSources) {
        this.rawDataSources = rawDataSources;
        this.dataSources = dataSources;
    }

    /**
     * Produces the manager over the resolved {@code DataSource}.
     *
     * @return a singleton manager, reusing the application's proxy when there is one
     * @throws IllegalStateException if no {@code DataSource} bean exists
     */
    @Produces
    @Singleton
    public JdbcTransactionManager jdbcTransactionManager() {
        DataSource dataSource = resolveDataSource();
        LOG.fine("Creating the JDBC transaction manager over the application DataSource");
        return new JdbcTransactionManager(dataSource);
    }

    private DataSource resolveDataSource() {
        DataSource defaultDataSource = dataSources.isUnsatisfied() ? null : dataSources.get();
        if (defaultDataSource instanceof TransactionAwareDataSourceProxy) {
            // the application already exposes the transaction-aware view: share it
            return defaultDataSource;
        }
        if (!rawDataSources.isUnsatisfied()) {
            return rawDataSources.get();
        }
        if (defaultDataSource != null) {
            return defaultDataSource;
        }
        throw new IllegalStateException(
                "No DataSource bean is available: declare a DataSource producer (a connection pool, "
                        + "or a TransactionAwareDataSourceProxy over one)");
    }
}
