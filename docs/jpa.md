# JPA Implementation

The `fluda-tx-jpa` module provides a `JpaTransactionManager` that drives transactions through a standard JPA `EntityManagerFactory`. It allows mixing JPA operations and plain JDBC SQL within the same `@Transactional` method, both operating on the same physical connection.

## How it works

`JpaTransactionManager` creates an `EntityManager`, begins a resource-local `EntityTransaction`, and extracts the underlying JDBC `Connection` via a configurable `ConnectionExtractor`. Both the EntityManager and the Connection are bound to the `TransactionContext`:

- **EntityManager** — bound under the `EntityManagerFactory` key
- **Connection** — bound under the `DataSource` key, enabling JDBC fallback through `TransactionAwareDataSourceProxy`

## Dependencies

```xml
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-jpa</artifactId>
    <version>${fluda.version}</version>
</dependency>
```

You also need a JPA provider (e.g. Hibernate) and a JDBC `DataSource`.

## Configuration

Produce an `EntityManagerFactory` and a `JpaTransactionManager` as CDI beans:

```java
import io.github.fludakit.tx.PlatformTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;
import io.github.fludakit.tx.jpa.JpaTransactionManager;

@ApplicationScoped
public class JpaConfig {

    private final EntityManagerFactory emf;

    public JpaConfig() {
        this.emf = Persistence.createEntityManagerFactory("myPU");
    }

    @Produces
    @ApplicationScoped
    public EntityManagerFactory entityManagerFactory() {
        return emf;
    }

    @Produces
    @ApplicationScoped
    public DataSource transactionAwareDataSource(DataSource rawDs) {
        return new TransactionAwareDataSourceProxy(rawDs);
    }

    @Produces
    @ApplicationScoped
    public PlatformTransactionManager transactionManager(DataSource rawDs) {
        return new JpaTransactionManager(emf, rawDs);
    }
}
```

## Injecting EntityManager

The `EntityManagerProducer` (auto-discovered by CDI) provides a transaction-scoped `EntityManager` proxy via `@Inject`:

```java
@ApplicationScoped
public class UserService {

    @Inject
    private EntityManager em;

    @Transactional
    public void createUser(User user) {
        em.persist(user);
    }
}
```

The proxy defers EntityManager lookup until first method invocation, so injection works at bean creation time before any transaction is active.

## ConnectionExtractor

The JPA spec does not standardize `em.unwrap(Connection.class)`. `ConnectionExtractor` is a pluggable strategy for extracting the JDBC `Connection` from an `EntityManager`.

The `DEFAULT` extractor tries `em.unwrap(Connection.class)` first, then falls back to Hibernate's `SessionImplementor.getJdbcConnectionAccess()` if Hibernate is on the classpath.

For other JPA providers, supply a custom extractor:

```java
public PlatformTransactionManager transactionManager(DataSource ds) {
    return new JpaTransactionManager(emf, ds, myCustomExtractor);
}
```

## JpaHelper

For programmatic access to the current EntityManager:

```java
// Single EntityManagerFactory
EntityManager em = JpaHelper.currentEntityManager();

// Multiple EntityManagerFactories — pass the raw (non-proxy) factory
EntityManager em = JpaHelper.currentEntityManager(emf);
```
