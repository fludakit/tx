package io.github.fludakit.tx.support;

import io.github.fludakit.tx.TransactionSavepoint;

import java.sql.Savepoint;
import java.util.Objects;

/**
 * {@link TransactionSavepoint} backed by a JDBC {@code Savepoint}.
 *
 * <p>Implementation detail of {@link JdbcSavepointManager}: the handle is only ever created by
 * that manager, which is also the only consumer of the wrapped {@link Savepoint}.</p>
 */
final class JdbcSavepoint implements TransactionSavepoint {

    private final Savepoint savepoint;
    private final String name;

    JdbcSavepoint(Savepoint savepoint, String name) {
        this.savepoint = Objects.requireNonNull(savepoint, "savepoint must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    Savepoint jdbcSavepoint() {
        return savepoint;
    }

    @Override
    public String name() {
        return name;
    }
}
