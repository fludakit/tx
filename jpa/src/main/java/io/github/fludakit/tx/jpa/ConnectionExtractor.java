package io.github.fludakit.tx.jpa;

import jakarta.persistence.EntityManager;

import java.sql.Connection;

/**
 * Strategy for extracting the underlying JDBC {@link Connection} from a JPA {@link EntityManager}.
 *
 * <p>The JPA specification does not mandate {@code em.unwrap(Connection.class)}. Different
 * providers handle this differently — or not at all. This functional interface allows applications
 * to supply a provider-specific extraction strategy while keeping {@link JpaTransactionManager}
 * provider-agnostic.</p>
 *
 * <p>The {@link #DEFAULT} implementation tries {@code em.unwrap(Connection.class)} first, then
 * falls back to Hibernate's {@code Session.getJdbcConnectionAccess()} via reflection (no compile-time
 * Hibernate dependency).</p>
 */
@FunctionalInterface
public interface ConnectionExtractor {

    Connection extract(EntityManager em);

    ConnectionExtractor DEFAULT = em -> {
        try {
            return em.unwrap(Connection.class);
        } catch (Exception e) {
            return extractViaHibernateSession(em);
        }
    };

    private static Connection extractViaHibernateSession(EntityManager em) {
        try {
            Object session = em.unwrap(em.getDelegate().getClass());
            Object access = session.getClass()
                    .getMethod("getJdbcConnectionAccess")
                    .invoke(session);
            return (Connection) access.getClass()
                    .getMethod("obtainConnection")
                    .invoke(access);
        } catch (Exception ex) {
            throw new jakarta.persistence.PersistenceException(
                    "Cannot extract JDBC Connection from EntityManager. "
                    + "Provide a custom ConnectionExtractor to JpaTransactionManager.", ex);
        }
    }
}
