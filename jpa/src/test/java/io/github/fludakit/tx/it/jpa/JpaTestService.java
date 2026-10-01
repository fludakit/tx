package io.github.fludakit.tx.it.jpa;

import io.github.fludakit.tx.jpa.JpaHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

@ApplicationScoped
public class JpaTestService {

    @Inject
    private EntityManagerFactory emf;

    @Inject
    private DataSource dataSource;

    @Transactional
    public void persistItem(long id, String name) {
        EntityManager em = JpaHelper.currentEntityManager(emf);
        em.persist(new TestEntity(id, name));
    }

    @Transactional
    public void persistAndThrow(long id, String name) {
        EntityManager em = JpaHelper.currentEntityManager(emf);
        em.persist(new TestEntity(id, name));
        throw new IllegalStateException("rollback");
    }

    @Transactional
    public void mixJpaAndJdbc(long jpaId, String jpaName, long jdbcId, String jdbcName) throws Exception {
        EntityManager em = JpaHelper.currentEntityManager(emf);
        em.persist(new TestEntity(jpaId, jpaName));

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO test_items (id, name) VALUES (?, ?)")) {
            stmt.setLong(1, jdbcId);
            stmt.setString(2, jdbcName);
            stmt.executeUpdate();
        }
    }
}
