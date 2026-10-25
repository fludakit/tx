package io.github.fludakit.tx.jpa;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import javax.sql.DataSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Bootstraps an {@link EntityManagerFactory} from a persistence unit plus the application's
 * {@link DataSource} — no duplicated JDBC coordinates, no {@code nonJtaDataSource} wiring by
 * hand, and the <b>same {@code DataSource} instance</b> the application's JDBC code uses, so
 * mixed JDBC/JPA work joins one transaction.
 *
 * <p>The DataSource is passed with the standard
 * {@link #NON_JTA_DATA_SOURCE jakarta.persistence.nonJtaDataSource} property, honored by every
 * Jakarta Persistence provider in resource-local mode. Providers that prefer a vendor key
 * (Hibernate's {@code hibernate.connection.datasource}) accept it through the map overloads.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DataSource dataSource = ...; // the application pool
 *
 * EntityManagerFactory emf = JpaBootstrap.create("myPU", dataSource);
 *
 * // with provider-specific extras
 * EntityManagerFactory emf = JpaBootstrap.create("myPU", dataSource, Map.of(
 *         "hibernate.hbm2ddl.auto", "update"));
 * }</pre>
 *
 * <p>The persistence unit (name, classes, provider) still resolves from {@code persistence.xml}
 * or, on Jakarta Persistence 3.2+, from a {@code PersistenceConfiguration} — the properties here
 * only carry what must reference live instances.</p>
 *
 * @see JpaTransactionManager
 */
public final class JpaBootstrap {

    /** Standard property carrying the resource-local {@link DataSource} instance. */
    public static final String NON_JTA_DATA_SOURCE = "jakarta.persistence.nonJtaDataSource";

    /** Hibernate-specific alternative to {@link #NON_JTA_DATA_SOURCE}. */
    public static final String HIBERNATE_DATA_SOURCE = "hibernate.connection.datasource";

    private JpaBootstrap() {
        throw new AssertionError("No instances");
    }

    /**
     * Creates an {@link EntityManagerFactory} for the persistence unit, bound to the given
     * {@link DataSource} through the standard {@link #NON_JTA_DATA_SOURCE} property.
     *
     * @param persistenceUnitName the persistence unit name ({@code persistence.xml})
     * @param dataSource          the application's DataSource, shared with JDBC code
     * @return the factory, backed by the given DataSource
     */
    public static EntityManagerFactory create(String persistenceUnitName, DataSource dataSource) {
        return create(persistenceUnitName, dataSource, Map.of());
    }

    /**
     * As {@link #create(String, DataSource)} with additional provider properties (dialect,
     * schema generation, vendor keys such as {@link #HIBERNATE_DATA_SOURCE}).
     *
     * @param persistenceUnitName the persistence unit name ({@code persistence.xml})
     * @param dataSource          the application's DataSource, shared with JDBC code
     * @param properties          additional provider properties, added after the DataSource binding
     * @return the factory, backed by the given DataSource
     */
    public static EntityManagerFactory create(String persistenceUnitName, DataSource dataSource,
                                              Map<String, Object> properties) {
        Map<String, Object> merged = new HashMap<>(properties);
        merged.put(NON_JTA_DATA_SOURCE, dataSource);
        return Persistence.createEntityManagerFactory(persistenceUnitName, merged);
    }

    /**
     * Creates an {@link EntityManagerFactory} from raw properties, for full manual control —
     * include {@link #NON_JTA_DATA_SOURCE} (or a vendor key) yourself to bind a DataSource.
     *
     * @param persistenceUnitName the persistence unit name ({@code persistence.xml})
     * @param properties          the provider properties, passed to
     *                            {@link Persistence#createEntityManagerFactory(String, Map)}
     * @return the factory
     */
    public static EntityManagerFactory create(String persistenceUnitName, Map<String, Object> properties) {
        return Persistence.createEntityManagerFactory(persistenceUnitName, properties);
    }
}
