package io.github.fludakit.tx.jpa;

import jakarta.persistence.PersistenceConfiguration;

/**
 * Adjusts the {@link PersistenceConfiguration} the CDI producer builds, and optionally names its
 * persistence unit.
 *
 * <p>Customizers are ordinary CDI beans; every one of them is applied, in turn, to the
 * configuration the producer already bound to the application's {@code DataSource}. They add what
 * only the application knows — managed classes, provider properties, schema generation:</p>
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class PersistenceConfig implements PersistenceConfigurationCustomizer {
 *
 *     @Override
 *     public String persistenceUnitName() {
 *         return "myPU";
 *     }
 *
 *     @Override
 *     public void customize(PersistenceConfiguration configuration) {
 *         configuration.managedClass(Order.class)
 *                 .property("hibernate.hbm2ddl.auto", "update");
 *     }
 * }
 * }</pre>
 *
 * <p>When no customizer names a unit, the producer uses
 * {@link PersistenceConfigurationProducer#DEFAULT_PERSISTENCE_UNIT_NAME}; when several customizers
 * name different units, startup fails rather than silently picking one.</p>
 *
 * @see PersistenceConfigurationProducer
 */
public interface PersistenceConfigurationCustomizer {

    /**
     * The persistence unit name to build, or {@code null} to leave the naming to another
     * customizer or to the default.
     *
     * @return the persistence unit name, or {@code null}
     */
    default String persistenceUnitName() {
        return null;
    }

    /**
     * Customizes the configuration. Called after the application's {@code DataSource} has been
     * bound, so a customizer may still replace either property.
     *
     * @param configuration the configuration being built, never {@code null}
     */
    void customize(PersistenceConfiguration configuration);
}
