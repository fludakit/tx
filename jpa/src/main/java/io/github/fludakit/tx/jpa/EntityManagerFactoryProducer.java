package io.github.fludakit.tx.jpa;

import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;

/**
 * CDI producer for the application's {@link EntityManagerFactory}.
 *
 * <p>The factory is created from the {@link PersistenceConfiguration} bean, so it is bound to the
 * same pool the transaction manager and the JDBC code use, and it is closed when the container
 * shuts down. Nothing else creates, owns, or closes factories: {@link JpaTransactionManager} only
 * borrows one, which keeps the persistence unit's lifecycle in a single place.</p>
 *
 * <p>The produced factory has {@link Singleton} scope because a factory is created once per
 * container and is too expensive — and too provider-specific — to proxy. An application that needs
 * a second unit declares its own {@code EntityManagerFactory} bean and {@code JpaTransactionManager}
 * bean, typically in a {@code @Dependent} producer per unit.</p>
 *
 * <p>The producer class itself is {@link ApplicationScoped} because {@code @Singleton} is not a
 * bean defining annotation: annotated discovery would skip the class, and no producer would be
 * registered at all.</p>
 *
 * @see PersistenceConfigurationProducer
 * @see PersistenceConfigurationCustomizer
 */
@ApplicationScoped
public class EntityManagerFactoryProducer {

    private static final Logger LOG = Logger.getLogger(EntityManagerFactoryProducer.class.getName());

    /**
     * Creates the producer.
     */
    public EntityManagerFactoryProducer() {
    }

    /**
     * Creates the factory for the application's persistence unit.
     *
     * @param configuration the configuration bean, already bound to the application pool
     * @return the container-wide factory
     */
    @Produces
    @Singleton
    public EntityManagerFactory entityManagerFactory(PersistenceConfiguration configuration) {
        LOG.fine(() -> "Creating the EntityManagerFactory for " + configuration.name());
        return configuration.createEntityManagerFactory();
    }

    /**
     * Closes the factory when the container shuts down, logging rather than rethrowing so one
     * failing close does not hide the others.
     *
     * @param entityManagerFactory the factory to close
     */
    void closeEntityManagerFactory(@Disposes EntityManagerFactory entityManagerFactory) {
        try {
            if (entityManagerFactory.isOpen()) {
                entityManagerFactory.close();
            }
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Failed to close the JPA EntityManagerFactory", ex);
        }
    }
}
