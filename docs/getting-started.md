# Getting Started

This guide covers adding FluDa Transaction Support to your project and setting up resource-local transactions in a CDI environment.

## Requirements

- JDK 21 or later.
- A CDI container (Weld SE for Java SE, Weld Servlet for Servlet containers, or a full Jakarta EE server).
- A JDBC `DataSource` (typically a connection pool like HikariCP).

## Adding dependencies

### For Java SE or Servlet environments

Add the three tx modules:

```xml
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-cdi</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The tx modules declare the CDI API and `jakarta.transaction-api` as `provided`: supply a CDI container (Weld SE, or a Servlet container with Weld) plus `jakarta.transaction-api` for the `@Transactional` annotation.

### For Jakarta EE environments

On a full Jakarta EE server, JTA is built in, so `jakarta.transaction.Transactional` is handled by the container — **no `fluda-tx` dependency is needed**. Just use the JDBC Client modules and let the container manage transactions.

## Setting up transactions

Resource-local transactions require three pieces:

1. A **raw connection pool** (e.g. HikariCP)
2. A **`TransactionAwareDataSource`** wrapping the pool — this is the `DataSource` your code injects
3. A **`PlatformTransactionManager`** implementation built from the same raw pool

### Wiring the DataSource and transaction manager

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.jdbc.DataSourceTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DatabaseConfig {

    private final HikariDataSource pool;

    public DatabaseConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        this.pool = new HikariDataSource(config);
    }

    @Produces
    @ApplicationScoped
    public DataSource dataSource() {
        return new TransactionAwareDataSourceProxy(pool);
    }

    @Produces
    @ApplicationScoped
    public PlatformTransactionManager transactionManager() {
        return new DataSourceTransactionManager(pool);
    }
}
```

!!! important
    The `DataSourceTransactionManager` and the `TransactionAwareDataSourceProxy` must share the **same raw pool** — the pool instance is the key that binds the transaction's `Connection` to the thread.

## Using `@Transactional`

Annotate a CDI bean method with `jakarta.transaction.Transactional`. Inject the transaction-aware `DataSource` (or `JdbcClient`) and use it as usual:

```java
import io.github.fludakit.jdbc.JdbcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class OrderService {

    @Inject
    JdbcClient client;

    @Transactional
    public void createOrder(String info) {
        client.sql("INSERT INTO orders (info) VALUES (:info)")
                .param("info", info)
                .update();
    }
}
```

The CDI interceptor auto-registered by `fluda-tx-cdi` drives the transaction around the method: it opens a `Connection`, disables auto-commit, and commits on success or rolls back on failure. Inside the method the proxy returns that bound `Connection` and suppresses `close()`, so try-with-resources blocks do not end the transaction prematurely.

## Transaction propagation

Control propagation with `Transactional.TxType` (`REQUIRED` by default):

```java
@Transactional(Transactional.TxType.REQUIRES_NEW)
public void doWork() {
    // always starts a new transaction
}
```

Supported types: `REQUIRED`, `REQUIRES_NEW`, `MANDATORY`, `SUPPORTS`, `NOT_SUPPORTED`, `NEVER`.

## Exception classification

By default the transaction rolls back on `RuntimeException` or `Error`, and commits on checked exceptions. Override with `rollbackOn` and `dontRollbackOn`:

```java
@Transactional(rollbackOn = CheckedBusinessException.class)
public void doWork() throws CheckedBusinessException {
}

@Transactional(dontRollbackOn = NoRollbackException.class)
public void doWork() {
}
```

## Next steps

See the [JDBC implementation](jdbc.md) page for DataSource-based transactions and the [JPA implementation](jpa.md) page for EntityManager-based transactions.
