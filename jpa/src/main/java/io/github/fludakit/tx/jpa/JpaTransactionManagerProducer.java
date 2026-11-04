package io.github.fludakit.tx.jpa;

import java.util.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManagerFactory;

/**
 * CDI producer for the {@link JpaTransactionManager} bean.
 *
 * <p>The manager only borrows the {@link EntityManagerFactory} produced by
 * {@link EntityManagerFactoryProducer} — the factory's lifecycle stays with that producer — and
 * binds the {@code EntityManager} and the underlying {@code Connection} to each transaction, so
 * {@code @Transactional} beans and the {@code EntityManager} producer work without further
 * wiring.</p>
 *
 * <p>The {@link ConnectionExtractor} is taken from the application when it declares one: a single
 * {@code ConnectionExtractor} bean replaces {@link ConnectionExtractor#DEFAULT}, which is what a
 * provider other than Hibernate needs to reach its transactional connection. Declaring more than
 * one extractor bean fails at startup, since the producer cannot choose between them.</p>
 *
 * <p>The produced manager has {@link Singleton} scope: the manager is stateless and the concrete
 * {@link JpaTransactionManager} type stays injectable, which an application needs when it wants to
 * register {@code TransactionSynchronization}s or run a programmatic boundary. Injecting
 * {@code TransactionManager} works too.</p>
 *
 * <p>The producer class itself is {@link ApplicationScoped} because {@code @Singleton} is not a
 * bean defining annotation: annotated discovery would skip the class, and no producer would be
 * registered at all.</p>
 *
 * @see JpaTransactionManager
 * @see EntityManagerProducer
 */
@ApplicationScoped
public class JpaTransactionManagerProducer {

    private static final Logger LOG = Logger.getLogger(JpaTransactionManagerProducer.class.getName());

    private final Instance<ConnectionExtractor> connectionExtractors;

    /**
     * @param connectionExtractors the application's extractors, possibly none
     */
    @Inject
    JpaTransactionManagerProducer(Instance<ConnectionExtractor> connectionExtractors) {
        this.connectionExtractors = connectionExtractors;
    }

    /**
     * Produces the manager over the container's factory, with the application's extractor when it
     * declares one.
     *
     * @param entityManagerFactory the factory produced by {@link EntityManagerFactoryProducer}
     * @return the container-wide JPA transaction manager
     */
    @Produces
    @Singleton
    public JpaTransactionManager jpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory, resolveConnectionExtractor());
    }

    private ConnectionExtractor resolveConnectionExtractor() {
        if (connectionExtractors.isUnsatisfied()) {
            return ConnectionExtractor.DEFAULT;
        }
        ConnectionExtractor extractor = connectionExtractors.get();
        LOG.fine(() -> "Using " + extractor.getClass().getName() + " as the ConnectionExtractor");
        return extractor;
    }
}
