# FluDa Transaction Support

[![Build](https://github.com/fludakit/tx/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/tx/actions/workflows/build.yml)

Container-agnostic declarative and programmatic transaction boundaries backed directly by a standard `DataSource` or JPA.

## Modules

- `fluda-tx-core`: SPI (`PlatformTransactionManager`), exceptions, and synchronization infrastructure.
- `fluda-tx-cdi`: CDI extension and `@Transactional` interceptor.
- `fluda-tx-jdbc`: Resource-local `DataSourceTransactionManager` and `TransactionAwareDataSourceProxy`.
- `fluda-tx-jpa`: JPA-based transaction manager (*coming soon*).

## Usage

Wrap your `DataSource` with `TransactionAwareDataSourceProxy`, register a `DataSourceTransactionManager`, and annotate methods with `@Transactional`:

```java
import io.github.fludakit.tx.jdbc.DataSourceTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;
import jakarta.transaction.Transactional;

DataSource raw = ...; // your connection pool
DataSource txAware = new TransactionAwareDataSourceProxy(raw);
PlatformTransactionManager txManager = new DataSourceTransactionManager(raw);

@Transactional
public void createOrder(Order order) {
    // runs inside a resource-local transaction
}
```

For CDI setup, transaction-phase callbacks and events, multiple DataSources, and mixing JPA with JDBC, see the [Transaction documentation](https://fludakit.github.io/documentation/tx/getting-started/).

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/documentation/tx/getting-started/) for getting started, the JDBC and JPA implementations, and advanced topics.
