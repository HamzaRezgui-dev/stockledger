# ADR 0003 — Writes run at SERIALIZABLE, with retry outside the transaction

Status: accepted · 2026-09-26

## Context

ADR 0001 makes on-hand stock a `SUM` over the ledger. Every outbound movement
therefore does a read-then-write:

1. read the current balance for (product, location, batch)
2. decide whether the requested quantity is available
3. insert the movement

Under Postgres's default `READ COMMITTED`, two concurrent sales of the last unit
both execute step 1 against a snapshot showing `1`, both pass step 2, and both
insert. Stock goes to `-1`: the business has sold something it does not have,
and a customer will be told at the counter that their paid-for item is missing.

`READ COMMITTED` does not prevent this. The two transactions never touch the
same *row* — they each `INSERT` a new one — so there is no row lock to contend
on. The conflict is over a **predicate** ("the set of movements matching this
product and location"), and that is precisely the class of anomaly read
committed permits.

## Decision

The write path runs at **`SERIALIZABLE`**, and retries on serialization failure.

```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public MovementId post(PostMovementCommand cmd) { … }
```

Postgres implements true Serializable Snapshot Isolation (SSI). It takes
predicate locks (`SIReadLock`) on what a transaction reads, detects the
read-write dependency cycle the two concurrent sales create, and aborts one with
`SQLSTATE 40001 serialization_failure`. The surviving transaction is correct; the
aborted one never happened at all.

We deliberately do **not** solve this with `SELECT … FOR UPDATE` on a balance
row, because there is no balance row to lock — that would mean reintroducing the
mutable counter ADR 0001 rejects, purely to have something to lock.

### Retry must live outside the transaction

This is the part that is easy to get wrong, and getting it wrong produces code
that looks correct and retries nothing.

A transaction aborted with `40001` is dead. Every subsequent statement on it
fails. So retrying *inside* the transactional method re-runs the work in the
same doomed transaction. The retry must begin a **new** transaction per attempt,
which means the retry boundary has to sit strictly outside the transaction
boundary:

```
LedgerController
  └─ LedgerFacade          @Retryable            ← retry boundary (no @Transactional)
       └─ LedgerService    @Transactional(SERIALIZABLE)   ← transaction boundary
```

Two consequences, both load-bearing:

- **These must be two separate beans.** Spring's annotations are implemented by
  proxies, and a self-invocation inside one class bypasses the proxy entirely. A
  `@Retryable` method calling a `@Transactional` method on `this` gets neither
  behaviour. Splitting the beans is not ceremony; it is the only arrangement
  that works.
- **A serialization failure can surface at `COMMIT`**, not at the statement that
  caused it. Since the commit happens as the `@Transactional` proxy returns, the
  exception propagates outward into the retry proxy, which is exactly where we
  need it.

Spring Framework 7 (Spring Boot 4) provides retry in core — `@Retryable` in
`org.springframework.resilience.annotation`, enabled by
`@EnableResilientMethods`. The separate `spring-retry` project is no longer
needed. Note the attribute is `maxRetries` (total attempts = 1 + `maxRetries`),
renamed from `spring-retry`'s `maxAttempts`.

Retries use a short randomised backoff. Two transactions that conflict and then
retry in lockstep will simply conflict again; jitter breaks the synchronisation.

### What we retry on

Only genuine serialization conflicts. Hibernate wraps Postgres's `40001` and
Spring's exception translation surfaces it as a
`ConcurrencyFailureException`, so that is the retry trigger — and
`@Retryable` matches nested causes, so the wrapping does not defeat it.

An `InsufficientStockException` is **not** retried. It is a deterministic
business answer, and retrying it five times only delays the same refusal.

## Defence in depth

The service prevents the race; a database trigger prevents the bug.
`stock_movements_zz_check_non_negative` recomputes the balance on every insert
and rejects anything that would drive it below zero (unless the location opts
in). So the invariant holds even for a code path that never goes through
`LedgerService` — a future service, a data-fix script, a manual `psql` session.

This is not redundancy for its own sake. The trigger's `SUM` is also what
creates the predicate read SSI needs, so the two mechanisms reinforce each other.

## Consequences

**Accepted costs**

- Writes can fail and retry, so the write path must be idempotent. Movements
  carry an `idempotency_key` with a unique index, letting a client safely retry
  a request that timed out without double-posting.
- Under heavy contention on one product, throughput is bounded by conflict rate.
  Acceptable: the target workload is a pharmacy or hardware store, where
  simultaneous writes to the *same* product at the *same* location are rare.
  If it ever bites, the fix is per-(product, location) serialisation, not weaker
  isolation.
- SSI tracks predicate locks in shared memory and escalates to coarser locks
  when it runs out — causing false conflicts. `max_pred_locks_per_transaction`
  is raised in `compose.yaml` to keep locks fine-grained.
- Reads stay at `READ COMMITTED`. Reporting does not need serializability, and
  paying for it on every dashboard query would be waste.

## Verification

This ADR's central claim is testable, so it is tested rather than asserted.
`LedgerConcurrencyIntegrationTest` starts two real threads on two real
connections, has both attempt to sell the same last unit, and asserts that
exactly one succeeds and the final balance is zero — never negative. A
companion test asserts the same against the trigger with the service bypassed.
