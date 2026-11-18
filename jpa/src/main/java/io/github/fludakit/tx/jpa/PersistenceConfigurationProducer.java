package io.github.fludakit.tx.jpa;

import io.github.fludakit.tx.support.RawPool;
import io.github.fludakit.tx.support.TransactionAwareDataSourceProxy;

import java.util.logging.Logger;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.PersistenceConfiguration;

/**
 * CDI producer for the {@link PersistenceConfiguration} the persistence unit is built from.
 *
 * <p>The configuration always names a unit and is always bound to the application's pool — the
 * {@link DataSource} qualified with {@link io.github.fludakit.tx.support.RawPool} when there is one, otherwise the default
 * unqualified {@code DataSource}. The pool instance is carried under the
 * {@link PersistenceConfiguration#JDBC_DATASOURCE} property and again under the Hibernate
 * property {@code hibernate.connection.datasource}, which is what providers such as Hibernate 7 read
 * to treat the unit as resource-local.</p>
 *
 * <p>An application fills in the rest — managed classes, provider properties, or the persistence
 * unit name — with one or more {@link PersistenceConfigurationCustomizer} beans. Without a name
 * the unit is {@link #DEFAULT_PERSISTENCE_UNIT_NAME}, which keeps a programmatic unit, with no
 * {@code persistence.xml} entry, working out of the box.</p>
 *
 * <p>The produced configuration has {@link Singleton} scope because
 * {@link PersistenceConfiguration} is a mutable builder with no no-arg constructor: a normal scope
 * would need a proxy the class cannot provide, and one configuration per container keeps the
 * customizers and the factory they feed in sync. An application that builds its own configuration
 * replaces this bean with an {@code @Alternative} producer carrying a {@code @Priority}.</p>
 *
 * <p>The producer class itself is {@link ApplicationScoped} because {@code @Singleton} is not a
 * bean defining annotation: annotated discovery would skip the class, and no producer would be
 * registered at all.</p>
 *
 * @see PersistenceConfigurationCustomizer
 * @see EntityManagerFactoryProducer
 */
@ApplicationScoped
public class PersistenceConfigurationProducer {

    /** The persistence unit name used when no customizer supplies one. */
    public static final String DEFAULT_PERSISTENCE_UNIT_NAME = "__defaultPU";

    /** The Hibernate property carrying the resource-local {@link DataSource} instance. */
    private static final String HIBERNATE_DATA_SOURCE = "hibernate.connection.datasource";

    private static final Logger LOG = Logger.getLogger(PersistenceConfigurationProducer.class.getName());

    private final Instance<DataSource> rawDataSources;
    private final Instance<DataSource> dataSources;
    private final Instance<PersistenceConfigurationCustomizer> customizers;

    /**
     * @param rawDataSources the pools qualified with {@link io.github.fludakit.tx.support.RawPool}, possibly unsatisfied
     * @param dataSources    the default pools, possibly unsatisfied
     * @param customizers    the application's customizers, possibly none
     */
    @Inject
    PersistenceConfigurationProducer(@RawPool Instance<DataSource> rawDataSources,
                                     Instance<DataSource> dataSources,
                                     Instance<PersistenceConfigurationCustomizer> customizers) {
        this.rawDataSources = rawDataSources;
        this.dataSources = dataSources;
        this.customizers = customizers;
    }

    /**
     * Produces the configuration, bound to the application pool and customized by every
     * {@link PersistenceConfigurationCustomizer} bean.
     *
     * @return the configuration the {@link EntityManagerFactoryProducer} builds its factory from
     * @throws IllegalStateException if no {@code DataSource} bean exists, if the only unqualified
     *                               one is a {@code TransactionAwareDataSourceProxy}, or if the
     *                               customizers name different persistence units
     */
    @Produces
    @Singleton
    public PersistenceConfiguration persistenceConfiguration() {
        DataSource dataSource = resolveDataSource();
        PersistenceConfiguration configuration = new PersistenceConfiguration(resolvePersistenceUnitName())
                .property(PersistenceConfiguration.JDBC_DATASOURCE, dataSource)
                .property(HIBERNATE_DATA_SOURCE, dataSource);
        for (PersistenceConfigurationCustomizer customizer : customizers) {
            customizer.customize(configuration);
        }
        LOG.fine(() -> "Built the persistence configuration for " + configuration.name());
        return configuration;
    }

    private String resolvePersistenceUnitName() {
        String name = null;
        for (PersistenceConfigurationCustomizer customizer : customizers) {
            String candidate = customizer.persistenceUnitName();
            if (candidate == null) {
                continue;
            }
            if (name != null && !name.equals(candidate)) {
                throw new IllegalStateException("Conflicting persistence unit names: " + name + " and " + candidate);
            }
            name = candidate;
        }
        return name != null ? name : DEFAULT_PERSISTENCE_UNIT_NAME;
    }

    private DataSource resolveDataSource() {
        if (!rawDataSources.isUnsatisfied()) {
            return requirePool(rawDataSources.get(), "@RawPool");
        }
        if (!dataSources.isUnsatisfied()) {
            return requirePool(dataSources.get(), "the default unqualified");
        }
        throw new IllegalStateException(
                "No DataSource bean is available: declare a DataSource producer, qualified with "
                        + "@RawPool when a TransactionAwareDataSourceProxy is the default DataSource");
    }

    private static DataSource requirePool(DataSource dataSource, String qualifier) {
        if (isTransactionAwareProxy(dataSource)) {
            throw new IllegalStateException("The " + qualifier + " DataSource is a "
                    + "TransactionAwareDataSourceProxy"
                    + "; a persistence unit must be built over the raw pool itself");
        }
        return dataSource;
    }

    private static boolean isTransactionAwareProxy(DataSource dataSource) {
        return dataSource instanceof TransactionAwareDataSourceProxy;
    }
}
