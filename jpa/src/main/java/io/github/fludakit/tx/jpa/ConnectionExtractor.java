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
 * falls back to Hibernate's {@code Session.getJdbcConnectionAccess()} if Hibernate is present.</p>
 */
@FunctionalInterface
public interface ConnectionExtractor {

    Connection extract(EntityManager em);

    boolean HIBERNATE_PRESENT = isHibernatePresent();

    ConnectionExtractor DEFAULT = em -> {
        try {
            return em.unwrap(Connection.class);
        } catch (Exception e) {
            return extractViaHibernateSession(em);
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

    private static Connection extractViaHibernateSession(EntityManager em) {
        if (!HIBERNATE_PRESENT) {
            throw new IllegalStateException(
                    "Cannot extract JDBC Connection from EntityManager. "
                    + "Hibernate is not available. Provide a custom ConnectionExtractor to JpaTransactionManager.");
        }
        
        org.hibernate.engine.spi.SessionImplementor session = 
                em.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
        org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess access = 
                session.getJdbcConnectionAccess();
        try {
            return access.obtainConnection();
        } catch (Exception ex) {
            throw new jakarta.persistence.PersistenceException(
                    "Cannot extract JDBC Connection from Hibernate Session. "
                    + "Provide a custom ConnectionExtractor to JpaTransactionManager.", ex);
        }
    }
}
