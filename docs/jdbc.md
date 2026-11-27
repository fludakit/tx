# JDBC Implementation

The `fluda-tx-jdbc` module provides a resource-local `DataSourceTransactionManager` and `TransactionAwareDataSourceProxy` for environments without JTA.

## How it works

The `DataSourceTransactionManager` obtains a `Connection` from the raw pool, disables auto-commit, and binds it to the current thread via `TransactionSynchronizationManager`. The `TransactionAwareDataSourceProxy` intercepts `getConnection()` calls:

- **Inside a transaction** — returns the bound `Connection` and suppresses `close()` so the transaction is not ended prematurely.
- **Outside a transaction** — delegates directly to the raw pool.

## Using with JDBC Client

The transaction support integrates seamlessly with FluDa JDBC Client. If you want to use the fluent `JdbcClient` API together with transaction management, add the JDBC Client modules:

```xml
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-jdbc-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-jdbc-cdi</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The `TransactionAwareDataSourceProxy` works transparently with `JdbcClient` — when you inject a `JdbcClient` in a `@Transactional` method, it automatically uses the transaction-bound connection.

## Java SE (Weld SE)

Bootstrap Weld SE as usual. The CDI extension in `fluda-tx-cdi` auto-registers the `@Transactional` interceptor.

```java
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;

try (WeldContainer container = new Weld().initialize()) {
    OrderService service = container.select(OrderService.class).get();
    service.createOrder("Java 25 Book");
}
```

Make sure `beans.xml` is present in `META-INF/` and the `DatabaseConfig` producer from the [getting started guide](getting-started.md) is on the classpath.

## Servlet container (Tomcat + Weld)

In a Servlet container, use Weld's servlet integration. The `DataSource` is typically obtained from JNDI:

```java
import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.jdbc.DataSourceTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DatabaseConfig {

    @Resource(name = "jdbc/myDS")
    private DataSource dataSource;

    @Produces
    @ApplicationScoped
    public DataSource transactionAwareDataSource() {
        return new TransactionAwareDataSourceProxy(dataSource);
    }

    @Produces
    @ApplicationScoped
    public PlatformTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }
}
```

Declare the JNDI DataSource in `META-INF/context.xml` (Tomcat) or the equivalent for your container.

## Transaction-phase callbacks

Register a `TransactionSynchronization` with `TransactionSynchronizationManager` to run code at a transaction phase. Registration must happen inside an active transaction.

```java
import io.github.fludakit.tx.support.TransactionSynchronization;
import io.github.fludakit.tx.support.TransactionSynchronizationManager;

@Transactional
public void doWork() {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            // commit-only follow-up
        }

        @Override
        public void afterCompletion(CompletionStatus status) {
            // COMMITTED or ROLLED_BACK
        }
    });
}
```

`TransactionSynchronizationManager.setRollbackOnly()` marks the current transaction for rollback without throwing.

## CDI transaction-phase events

Observers can react to the transaction outcome with `@Observes(during = TransactionPhase.X)`. An event fired inside an active transaction is deferred and delivered at the matching phase.

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

@ApplicationScoped
public class OrderNotifier {

    public void onCreated(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderCreated event) {
        // runs only if the transaction commits
    }
}
```

Supported phases are `BEFORE_COMPLETION`, `AFTER_SUCCESS`, `AFTER_FAILURE`, and `AFTER_COMPLETION`. `IN_PROGRESS` observers fire immediately.
