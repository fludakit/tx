# Transaction Support

Part of the [FluDa](https://fludakit.github.io/) toolkit — [module source](https://github.com/fludakit/tx).

FluDa Transaction Support provides container-agnostic declarative and programmatic transaction boundaries backed directly by a standard `DataSource` or JPA.

## How it works

Depending on the runtime, applications can participate in database transactions in two ways:

- **Java SE or a Servlet container** — use the `fluda-tx` modules for *resource-local* transactions. A CDI interceptor honors `jakarta.transaction.Transactional` over a plain JDBC `DataSource`, with no JTA required.
- **A full Jakarta EE runtime** (WildFly, GlassFish, Payara, Open Liberty, ...) — the container ships a JTA implementation, so `jakarta.transaction.Transactional` works out of the box and the `fluda-tx` modules are unnecessary.

## Modules

| Module | Description |
|--------|-------------|
| `fluda-tx-core` | Core SPI: `PlatformTransactionManager`, exceptions, and synchronization infrastructure. |
| `fluda-tx-cdi` | CDI extension and `@Transactional` interceptor. |
| `fluda-tx-jdbc` | Resource-local `DataSourceTransactionManager` and `TransactionAwareDataSourceProxy`. |
| `fluda-tx-jpa` | JPA-based transaction manager with `EntityManager` integration. |

## Key concepts

- **`PlatformTransactionManager`** — the SPI that abstracts transaction begin/commit/rollback. The `fluda-tx-jdbc` module provides a `DataSourceTransactionManager` for resource-local JDBC transactions.
- **`TransactionAwareDataSourceProxy`** — wraps a raw `DataSource` so that `getConnection()` returns the transaction-bound connection inside an active transaction, and `close()` is suppressed.
- **CDI interceptor** — auto-registered by `fluda-tx-cdi`, drives the transaction lifecycle around `@Transactional` methods.

See the [getting started](getting-started.md) guide to add dependencies and set up a minimal example, the [JDBC implementation](jdbc.md) page for DataSource-based transactions, the [JPA implementation](jpa.md) page for EntityManager-based transactions, and [advanced topics](advanced.md) for multiple DataSources and mixing JPA with JDBC.
