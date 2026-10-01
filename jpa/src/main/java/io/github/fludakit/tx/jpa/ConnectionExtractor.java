package io.github.fludakit.tx.jpa;

import java.sql.Connection;
import jakarta.persistence.EntityManager;

/**
 * Strategy for extracting the underlying JDBC {@link Connection} from a JPA {@link EntityManager}.
 *
 * <p>The JPA specification does not mandate {@code em.unwrap(Connection.class)}. Different
 * providers handle this differently — or not at all. This functional interface allows applications
 * to supply a provider-specific extraction strategy while keeping {@link JpaTransactionManager}
 * provider-agnostic.</p>
 *
 * <p>The {@link #DEFAULT} implementation tries {@code em.unwrap(Connection.class)} first, then
 * falls back to Hibernate's {@code Session.getJdbcConnectionAccess()} if Hibernate is present.</p>
 */
@FunctionalInterface
public interface ConnectionExtractor {

    Connection extract(EntityManager em);

    ConnectionExtractor DEFAULT = em -> {
        try {
            return em.unwrap(Connection.class);
        } catch (Exception e) {
            if (isHibernatePresent()) {
                return extractFromHibernateSession(em);
            }
            throw new IllegalStateException(
                    "Cannot extract JDBC Connection from EntityManager. "
                            + "Hibernate is not available. Provide a custom ConnectionExtractor to JpaTransactionManager.");
        }
    };

    private static boolean isHibernatePresent() {
        try {
            Class.forName("org.hibernate.engine.spi.SessionImplementor");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Connection extractFromHibernateSession(EntityManager em) {
        try {
            return em.unwrap(org.hibernate.engine.spi.SessionImplementor.class)
                    .getJdbcConnectionAccess()
                    .obtainConnection();
        } catch (Exception ex) {
            throw new jakarta.persistence.PersistenceException(
                    "Cannot extract JDBC Connection from Hibernate Session. "
                            + "Provide a custom ConnectionExtractor to JpaTransactionManager.", ex);
        }
    }
}
