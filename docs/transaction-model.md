# The synchronous transaction model

This document describes the design of `fludakit-tx` after the resource-local redesign. It is
deliberately narrow: **synchronous, resource-local transactions only**. There is no reactive
API, no `@Async`, no distributed/XA coordination, and no connection-pool abstraction layer.

The design borrows its core idea from Micronaut Data's `data-tx` module
(`io.micronaut.transaction`), specifically the synchronous path:
`SynchronousTransactionManager`, `TransactionOperations`, `TransactionStatus`,
`AbstractTransactionOperations`, `DefaultTransactionStatus`, `SavepointManager`,
`TransactionSynchronization`. Everything below either mirrors that model or explains why this
codebase's version is smaller.

## The one idea

> A transaction **is** a status object.

There is exactly one object per transaction. It carries the transaction definition, the bound
resource, the rollback-only flags, the registered synchronizations, the optional savepoint, and
the completion state. That object is what the interceptor creates, what the template binds to
the current thread/scope, and what the unit of work receives.

Everything else in the model exists to create, bind, complete, or observe that object:

```
TransactionDefinition ──┐
                        ├──> TransactionStatus ──> unit of work ──> commit | rollback
            resource ───┘
```

Consequences:

- No separate `TransactionContext` alongside a passive `TransactionStatus`. That split made
  the status a record and forced the lifecycle state machine to live somewhere else.
- No `TransactionTemplate` owned by the front-ends. The template is the manager itself.
- Propagation is not a `switch` over a passive object; the status kind decides what "commit",
  "rollback", and "setRollbackOnly" mean.

## Layers

| Package                     | Contents                                                       | Visibility      |
|-----------------------------|----------------------------------------------------------------|-----------------|
| `io.github.fludakit.tx`     | API: definition, status, manager, operations, savepoints, exceptions | public, application-facing |
| `io.github.fludakit.tx.support` | Runtime: the template (`AbstractTransactionOperations`), the status implementation, the scope holder | public but internal-facing |
| `io.github.fludakit.tx.jdbc` | `JdbcTransactionManager`, `TransactionAwareDataSourceProxy`    | public          |
| `io.github.fludakit.tx.jpa`  | `JpaTransactionManager`, `JpaHelper`, `EntityManagerProducer`   | public          |
| `io.github.fludakit.tx.cdi`  | `@Transactional` interceptor, `@TransactionScoped` context, transactional event dispatch, metadata registry | public |

Dependencies point one way: `jdbc`, `jpa`, `cdi` → `core`. `core` knows nothing about JDBC,
JPA, or CDI (the `jakarta.enterprise` dependency is `provided` and only used for
`TransactionScoped` type references in documentation).

## The API surface

### `TransactionDefinition`

Immutable transaction attributes: propagation (`Propagation`), `rollbackOn` /
`dontRollbackOn`, and `readOnly`.

`Propagation` carries the six `jakarta.transaction.Transactional.TxType` behaviours plus
`NESTED`, which Jakarta Transactions deliberately leaves out of the annotation. The CDI
front-end resolves the annotation with `Propagation.of(TxType)`; `NESTED` is reachable only
programmatically, through `executeNested(...)`.

It owns the rollback decision: `rollbackOn(Throwable)` applies the configured rules and falls
back to `RuntimeException`/`Error`, unwrapping `InvocationTargetException` and
`ExecutionException` first. This is the rule set the Jakarta `@Transactional` specification
prescribes, and it is also Spring's `TransactionTemplate` default — so the declarative and the
programmatic front-end use the same rule set. They cannot disagree.

`withPropagation(...)` and `withReadOnly(...)` return copies; a definition is never mutated.

### `TransactionStatus<T>`

The transaction handle. `T` is the resource type the manager binds (`Connection` for JDBC,
`EntityManager` for JPA).

```
definition:       TransactionDefinition
resource:         T?                       // null outside an actual transaction
isNewTransaction: boolean                  // begun here, so completion belongs to this caller
isNestedTransaction: boolean               // savepoint-scoped section inside an outer transaction
isActualTransactionActive: boolean         // false for the shared no-transaction status
isRollbackOnly / isLocalRollbackOnly / isGlobalRollbackOnly
setRollbackOnly()
isCompleted
isSavepointSupported / createSavepoint / rollbackToSavepoint / releaseSavepoint
registerSynchronization(TransactionSynchronization)
getAttribute(Class<A>) / putAttributeIfAbsent(Class<A>, A)   // typed attribute slots
```

The typed attribute slots exist for exactly two consumers: `@TransactionScoped` bean stores and
the CDI transactional event dispatcher. They are not a general-purpose map.

### `TransactionManager<T>`

`TransactionManager<T> extends TransactionalOperations<T>` — one object, two views. The
operations view is what application code and the interceptor use; the manager view is the
low-level demarcation API (`getTransaction`, `commit`, `rollback`) used for manual control and
by the template itself.

```java
Optional<TransactionStatus<T>> findTransactionStatus();  // the ambient, actual transaction
T getResource();                                         // its resource, or null
<R> R execute(TransactionDefinition, TransactionExecution<T,R>) throws Exception;
TransactionStatus<T> getTransaction(TransactionDefinition);
void commit(TransactionStatus<T>);
void rollback(TransactionStatus<T>);
```

### `AbstractTransactionOperations<T>`

The template. It implements propagation, suspension, begin/complete, synchronization
dispatch, and completion state. Concrete managers implement only primitive hooks:

```java
protected abstract void doBegin(DefaultTransactionStatus<T> status);
protected abstract void doCommit(DefaultTransactionStatus<T> status);
protected abstract void doRollback(DefaultTransactionStatus<T> status);

// optional
protected void doNestedBegin(DefaultTransactionStatus<T> status);    // default: create a savepoint
protected void doNestedCommit(DefaultTransactionStatus<T> status);    // default: release it
protected void doNestedRollback(DefaultTransactionStatus<T> status);  // default: roll back to it
protected void doSuspend(DefaultTransactionStatus<T> status);         // default: no-op
protected void doResume(DefaultTransactionStatus<T> status);          // default: no-op
protected SavepointManager newSavepointManager(DefaultTransactionStatus<T> status); // default: null
```

The hooks receive the concrete `DefaultTransactionStatus<T>` because they mutate the
transaction's own state (resource, savepoint, rollback flags); `TransactionStatus` stays the
read/participate surface for application code.

The default nested hooks use the status's savepoint API, so a manager that supplies a
`SavepointManager` gets `NESTED` propagation for free.

## Lifecycle

```
execute(definition, work)
  │
  ├─ resolve ambient status
  ├─ dispatch on definition.propagation()
  │
  ├─ join:        work(existing.resource, existing)          // nothing to complete
  ├─ no-tx:       work(null, NO_TRANSACTIONAL)               // SUPPORTS / NOT_SUPPORTED / NEVER
  └─ new tx:      create status ──► bind ──► doBegin
                                     │
                                     ├─ work
                                     ├─ failure && definition.rollbackOn(failure)? rollback : commit
                                     └─ unbind
```

Completing a new transaction:

1. already completed → `IllegalTransactionStateException`
2. local rollback-only → rollback
3. global rollback-only (a joined participant marked it) → rollback, then
   `UnexpectedRollbackException` (the caller asked for a commit that cannot happen)
4. `beforeCommit(readOnly)` → `beforeCompletion()` → `doCommit`
5. `afterCommit()` → `afterCompletion(COMMITTED)`
6. `doCleanup` is not a hook: managers register a `TransactionSynchronization` for release, so
   resource cleanup runs on the same path for every manager and cannot be forgotten.

Failure while committing marks the status `UNKNOWN` and fires `afterCompletion(UNKNOWN)`; a
failure while rolling back does the same. The transaction is completed exactly once.

## Propagation

| `Propagation`  | No ambient transaction            | Ambient transaction                    |
|---------------|-----------------------------------|----------------------------------------|
| `REQUIRED`    | begin                             | join                                   |
| `REQUIRES_NEW`| begin                             | suspend, begin, resume                 |
| `SUPPORTS`    | no-tx status                      | join                                   |
| `NOT_SUPPORTED`| no-tx status                     | suspend, no-tx status, resume          |
| `MANDATORY`   | `IllegalTransactionStateException`| join                                   |
| `NEVER`       | no-tx status                      | `TransactionUsageException`            |
| `NESTED`      | begin                             | savepoint-scoped section               |

`join` reuses the *same status object*. There is no wrapper: a joined call must not complete
the transaction, and `isNewTransaction()` is `false` on it, which is exactly what the caller
sees. A `NESTED` section is the one case that needs a distinct object, because it owns a
savepoint and its rollback-only flag must stay local.

Suspension is scope-based: the holder restores the outer status when the inner scope closes, so
`REQUIRES_NEW` and `NOT_SUPPORTED` cannot leak. `doSuspend`/`doResume` exist for managers that
must detach something physical (for example an OSIV-managed `EntityManager`); JDBC and JPA in
this repository have nothing to detach and leave them as no-ops.

## Savepoints

`SavepointManager` is a property of the *manager*, attached to each new status at begin time.
`TransactionStatus` exposes `createSavepoint` / `rollbackToSavepoint` / `releaseSavepoint`
over it, and reports `isSavepointSupported()`.

This is a deliberate change from the old model, where the savepoint manager was passed into a
static status factory: the capability now travels with the transaction it belongs to, and
`NESTED` is just the default nested hooks over that capability.

`executeNested(work)` on `TransactionalOperations` is the programmatic entry point: it runs
`NESTED` when a transaction is active and a plain `REQUIRED` transaction otherwise (there is
nothing to nest into).

## Synchronizations

`TransactionSynchronization` is the callback contract (`beforeCommit`, `beforeCompletion`,
`afterCommit`, `afterCompletion(Status)`), with `Status` = `COMMITTED`, `ROLLED_BACK`,
`UNKNOWN`. This replaces the old `TransactionCallback`, whose `CompletionStatus` duplicated the
status state.

A synchronization registered on a joined call is attached to the transaction that is actually
running (the outer one), so it fires once, at the real boundary. A synchronization registered
on a nested section is attached to the outer transaction as well: a savepoint section has no
completion of its own.

## Transactional CDI events

Standard CDI transactional observers — `@Observes(during = TransactionPhase.AFTER_SUCCESS)`, and
friends — are the public surface; no annotation of our own is involved. The container drives
those observers itself only for JTA transactions, and the transaction model here is
resource-local, so the CDI module bridges the gap:

1. At deployment, `TransactionalCdiExtension` vetoes every observer whose phase is not
   `IN_PROGRESS` and registers one replacement per observer. The replacement keeps the original
   observed type and qualifier set — so CDI still does the type and qualifier matching — and
   reports its own phase as `IN_PROGRESS`.
2. When an event reaches a replacement, the payload is handed to the transaction's
   `TransactionEventDispatcher` together with the observer and its declared phase.
3. The dispatcher is a `TransactionSynchronization` that holds the payloads and delivers them at
   the matching callback:

| CDI phase            | Delivered from          | When                          |
|----------------------|-------------------------|-------------------------------|
| `BEFORE_COMPLETION`  | `beforeCompletion()`    | before commit or rollback     |
| `AFTER_SUCCESS`      | `afterCommit()`         | after the resource committed  |
| `AFTER_FAILURE`      | `afterCompletion()`     | after a rollback              |
| `AFTER_COMPLETION`   | `afterCompletion()`     | after either outcome          |

The dispatcher installs itself on the first event of the transaction, bound to the status as a
typed attribute, rather than being installed by the interceptor. That is what makes transactional
observers work for **every** boundary: the `@Transactional` interceptor (`5. afterCommit()` →
`afterCompletion(COMMITTED)` in the lifecycle above) and a programmatic
`manager.execute(...)` call behave identically. A joined participant and a `NESTED` section share
the attribute and the synchronization registration with the transaction that is actually running,
so each event is delivered exactly once, at the real boundary.

An event fired with no actual transaction active is dropped: a transactional observer must not run
without a transaction. A failing observer is logged and neither stops the other observers nor
changes the transaction's outcome.

## Exceptions

All failures derive from `TransactionException extends jakarta.transaction.TransactionalException`,
so the declarative and programmatic paths satisfy the same contract.

| Exception                              | Meaning                                                        |
|----------------------------------------|----------------------------------------------------------------|
| `IllegalTransactionStateException`     | `MANDATORY` without a transaction; double completion; commit/rollback of a non-owning status |
| `TransactionUsageException`            | `NEVER` inside a transaction                                   |
| `NestedTransactionNotSupportedException` | `NESTED` without savepoint support                           |
| `UnexpectedRollbackException`          | commit requested for a transaction marked rollback-only        |
| `TransactionSystemException`           | the resource failed to begin/commit/roll back (wraps the cause) |

## What each module contributes

**core** — the model above, and nothing else. The scope holder (`TransactionStatusHolder`) binds
the current status per thread: an `InheritableThreadLocal` carrying a revocable binding on
JDK 21, a `ScopedValue` on JDK 25 (multi-release overlay). The binding is closed when the scope
ends, so a transaction never outlives its boundary, even when a raw thread inherited it.

**jdbc** — `JdbcTransactionManager` opens a `Connection` in `doBegin` (auto-commit off, optional
read-only), binds it to the status, and registers a synchronization that resets and closes it.
Savepoints map to JDBC savepoints through the shared `JdbcSavepointManager`, which also tolerates
drivers that reject `releaseSavepoint`. `TransactionAwareDataSourceProxy` lets plain
`dataSource.getConnection()` code join the transaction; outside one it delegates to the pool,
credentials included.

**jpa** — `JpaTransactionManager` creates an `EntityManager`, begins its `EntityTransaction`,
binds both the `EntityManager` and the underlying `Connection` (so mixed JDBC/JPA code shares
one transaction), and flushes on commit. Savepoints are JDBC savepoints over the extracted
connection, through the same `JdbcSavepointManager`.

The manager is built from either an existing `EntityManagerFactory`, or — preferred when the
application owns a pool — from a persistence unit name plus that `DataSource`:

```java
JpaTransactionManager txManager = new JpaTransactionManager("myPU", dataSource);
```

The second form creates the factory through `JpaBootstrap`, which passes the `DataSource` as
`jakarta.persistence.nonJtaDataSource`. Callers never hand over the factory *and* the pool, and
the persistence unit cannot accidentally connect to a different pool. The manager is
`AutoCloseable` and closes the factory only when it created it; a factory passed in stays the
caller's.

**cdi** — the interceptor resolves the precomputed definition and calls
`manager.execute(definition, ...)`; everything else is deployment-time wiring. The
`TransactionEventDispatcher` described above installs itself when the first transactional event is
fired, so the interceptor carries no event code at all. `TransactionScopeContext` implements
`@TransactionScoped` over the status attribute slots.

## Out of scope (on purpose)

- reactive / async transaction propagation
- `@Transactional` on `@TransactionScoped` beans, timeouts, isolation levels, names
- XA / distributed transactions, transaction suspension of a physical connection
- nested transactions as independent transactions (only savepoint semantics)

These can be added later without reshaping the model: they are additional status kinds,
additional manager hooks, or additional definition attributes.
