# FluDa Transaction Support

Container-agnostic declarative and programmatic transaction boundaries backed directly by a standard `DataSource` or JPA.

## Modules

- `fluda-tx-core`: SPI (`PlatformTransactionManager`), exceptions, and synchronization infrastructure.
- `fluda-tx-cdi`: CDI extension and `@Transactional` interceptor.
- `fluda-tx-jdbc`: Resource-local `DataSourceTransactionManager` and `TransactionAwareDataSourceProxy`.
- `fluda-tx-jpa`: JPA-based transaction manager (*coming soon*).

## Usage

### 1. Add dependencies

```xml
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-core</artifactId>
    <version>${fluda.version}</version>
</dependency>
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-cdi</artifactId>
    <version>${fluda.version}</version>
</dependency>
<dependency>
    <groupId>io.github.fludakit</groupId>
    <artifactId>fluda-tx-jdbc</artifactId>
    <version>${fluda.version}</version>
</dependency>
```

### 2. Set up a `TransactionAwareDataSource` and a `PlatformTransactionManager`

```java
import io.github.fludakit.tx.jdbc.DataSourceTransactionManager;
import io.github.fludakit.tx.jdbc.TransactionAwareDataSourceProxy;

DataSource raw = ...; // your connection pool
DataSource txAware = new TransactionAwareDataSourceProxy(raw);
PlatformTransactionManager txManager = new DataSourceTransactionManager(raw);
```

### 3. Use `@Transactional`

```java
@Transactional
public void createOrder(Order order) {
    // runs inside a resource-local transaction
}
```

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/) for installation, quickstart, and full API reference.
