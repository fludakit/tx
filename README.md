# FluDa Transaction Support

[![Build](https://github.com/fludakit/tx/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/tx/actions/workflows/build.yml)

Container-agnostic declarative and programmatic transaction boundaries backed directly by a standard `DataSource` or JPA.

## Modules

- `fluda-tx-core`: API (`TransactionManager`, `TransactionalOperations`, `TransactionStatus`, exceptions) plus the `support` package holding `AbstractTransactionOperations`, `DefaultTransactionStatus`, and the scope binding.
- `fluda-tx-cdi`: CDI extension and `@Transactional` interceptor.
- `fluda-tx-jdbc`: Resource-local `JdbcTransactionManager` and `TransactionAwareDataSourceProxy`.
- `fluda-tx-jpa`: Resource-local `JpaTransactionManager`, `JpaHelper`, and `JpaBootstrap`.

## Usage

Wrap your `DataSource` with `TransactionAwareDataSourceProxy`, register a `JdbcTransactionManager`, and annotate methods with `@Transactional`:

```java
import io.github.fludakit.tx.jdbc.JdbcTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;
import jakarta.transaction.Transactional;

DataSource raw = ...; // your connection pool
JdbcTransactionManager txManager = new JdbcTransactionManager(raw);

@Transactional
public void createOrder(Order order) {
    // runs inside a resource-local transaction
}
```

The manager *is* the template: it implements `TransactionalOperations`, so programmatic
boundaries need no wrapper object.

```java
txManager.execute(TransactionDefinition.withDefaults(), (connection, status) -> {
    // the bound Connection is handed to the callback
    return repository.insert(connection, order);
});
```

`TransactionDefinition` also carries the propagation, the rollback rules, and the read-only
hint, so the programmatic and the declarative front-ends cannot disagree.

For CDI setup, transaction-phase callbacks and events, multiple DataSources, and mixing JPA with JDBC, see the [Transaction documentation](https://fludakit.github.io/documentation/tx/getting-started/).

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/documentation/tx/getting-started/) for getting started, the JDBC and JPA implementations, and advanced topics.
