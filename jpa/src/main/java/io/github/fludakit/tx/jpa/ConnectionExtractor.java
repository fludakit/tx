package io.github.fludakit.tx.jpa;

import java.sql.Connection;
import jakarta.persistence.EntityManager;

/**
 * Strategy for extracting the JDBC {@link Connection} the transaction is using from a JPA
 * {@link EntityManager}.
 *
 * <p>The JPA specification does not mandate {@code em.unwrap(Connection.class)}. Different
 * providers handle this differently — or not at all. This functional interface allows applications
 * to supply a provider-specific extraction strategy while keeping {@link JpaTransactionManager}
 * provider-agnostic.</p>
 *
 * <p>Extraction must happen inside the transaction (after {@code doBegin}): the returned
 * connection is the one the transaction commits and rolls back, and the one savepoints are
 * created on. A connection obtained some other way — for example a second one borrowed from the
 * pool — would be neither transactional nor safe to hold.</p>
 *
 * <p>The {@link #DEFAULT} implementation drives the work through Hibernate's
 * {@code Session.doReturningWork}, which hands out the connection the session's transaction
 * holds. Hibernate's {@code EntityManager.unwrap(Connection.class)} and
 * {@code Session.getJdbcConnectionAccess().obtainConnection()} are deliberately not used: both
 * answer with a separate pooled connection in auto-commit mode, so a savepoint created on it
 * would not affect the transaction and the connection would never be released.</p>
 *
 * @see JpaTransactionManager
 */
@FunctionalInterface
public interface ConnectionExtractor {

    /**
     * Extracts the transaction's JDBC connection.
     *
     * @param em the EntityManager of a transaction that has already begun
     * @return the connection the transaction uses
     * @throws IllegalStateException if the provider exposes no way to reach it
     */
    Connection extract(EntityManager em);

    ConnectionExtractor DEFAULT = entityManager -> {
        if (isHibernatePresent()) {
            try {
                return entityManager.unwrap(org.hibernate.Session.class)
                        .doReturningWork(connection -> connection);
            } catch (RuntimeException hibernateFailure) {
                return extractByUnwrap(entityManager, hibernateFailure);
            }
        }
        return extractByUnwrap(entityManager, null);
    };

    private static Connection extractByUnwrap(EntityManager entityManager, RuntimeException previousFailure) {
        try {
            return entityManager.unwrap(Connection.class);
        } catch (RuntimeException ex) {
            if (previousFailure != null) {
                ex.addSuppressed(previousFailure);
            }
            throw new IllegalStateException(
                    "Cannot extract the JDBC Connection the transaction is using from the EntityManager. "
                            + "Provide a custom ConnectionExtractor to JpaTransactionManager.", ex);
        }
    }

    private static boolean isHibernatePresent() {
        try {
            Class.forName("org.hibernate.engine.spi.SessionImplementor");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
