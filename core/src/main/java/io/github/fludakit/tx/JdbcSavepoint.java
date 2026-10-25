package io.github.fludakit.tx;

import java.sql.Savepoint;
import java.util.Objects;

/**
 * {@link TransactionSavepoint} backed by a JDBC {@code Savepoint}.
 */
public final class JdbcSavepoint implements TransactionSavepoint {

    private final Savepoint savepoint;
    private final String name;

    public JdbcSavepoint(Savepoint savepoint, String name) {
        this.savepoint = Objects.requireNonNull(savepoint, "savepoint must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public Savepoint jdbcSavepoint() {
        return savepoint;
    }

    @Override
    public String name() {
        return name;
    }
}
