package io.github.fludakit.tx.jdbc;

import io.github.fludakit.tx.RawDataSource;

import java.util.logging.Logger;
import javax.sql.DataSource;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * CDI producer for a {@link JdbcTransactionManager} over the application's pool.
 *
 * <p>The pool is resolved once, at bean creation: a {@link DataSource} qualified with
 * {@link RawDataSource} wins, and an unqualified {@code DataSource} is used when no raw bean is
 * declared. This lets an application expose a {@link TransactionAwareDataSourceProxy} as its
 * default {@code DataSource} — what plain JDBC code should inject — while the manager keeps
 * opening real connections from the pool behind it.</p>
 *
 * <pre>{@code
 * @Produces
 * @RawDataSource
 * DataSource pool() {
 *     return hikariDataSource;
 * }
 *
 * @Produces
 * DataSource dataSource() {
 *     return new TransactionAwareDataSourceProxy(pool());
 * }
 * }</pre>
 *
 * <p>With a single pool and no qualifiers at all, the unqualified bean is used as is; the producer
 * fails at startup if that bean is a {@code TransactionAwareDataSourceProxy}, because a manager
 * built over the proxy would commit the same connection twice and never return it to the
 * pool.</p>
 *
 * <p>The produced manager has {@link Singleton} scope: it is thread-safe and stateless apart from
 * the pool, so one instance per container is enough, and the concrete
 * {@link JdbcTransactionManager} type stays injectable.</p>
 *
 * @see RawDataSource
 */
@Singleton
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
     * Produces the manager over the resolved pool.
     *
     * @return a singleton manager over the raw pool
     * @throws IllegalStateException if no {@code DataSource} bean exists, or the only unqualified
     *                               one is a {@link TransactionAwareDataSourceProxy}
     */
    @Produces
    @Singleton
    public JdbcTransactionManager jdbcTransactionManager() {
        DataSource dataSource = resolveDataSource();
        LOG.fine("Creating the JDBC transaction manager over the application pool");
        return new JdbcTransactionManager(dataSource);
    }

    private DataSource resolveDataSource() {
        if (!rawDataSources.isUnsatisfied()) {
            return requirePool(rawDataSources.get(), "@RawDataSource");
        }
        if (!dataSources.isUnsatisfied()) {
            return requirePool(dataSources.get(), "the default unqualified");
        }
        throw new IllegalStateException(
                "No DataSource bean is available: declare a DataSource producer, qualified with "
                        + "@RawDataSource when a TransactionAwareDataSourceProxy is the default DataSource");
    }

    private static DataSource requirePool(DataSource dataSource, String qualifier) {
        if (dataSource instanceof TransactionAwareDataSourceProxy) {
            throw new IllegalStateException("The " + qualifier + " DataSource is a "
                    + TransactionAwareDataSourceProxy.class.getSimpleName()
                    + "; a transaction manager must be built over the raw pool itself");
        }
        return dataSource;
    }
}
