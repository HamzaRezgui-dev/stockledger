# ADR 0001 — Stock is an append-only ledger, not a mutable quantity

Status: accepted · 2026-09-26

## Context

The obvious inventory schema is a mutable counter:

```sql
products (id, name, quantity_on_hand)   -- UPDATE products SET quantity_on_hand = ...
```

Every inventory system that starts this way develops the same three failures,
and all three are things a client notices and stops paying for.

**1. Phantom stock, with no way to find out why.** The number says 14, the shelf
has 11. Nobody can answer *when* the three vanished, *who* was involved, or
*which* operation was wrong, because each `UPDATE` destroyed the previous value.
The only recovery is a full physical recount, which costs a day of labour and
recurs monthly.

**2. Lost updates under concurrency.** Two terminals sell the last unit at the
same moment. Both read `quantity = 1`, both write `quantity = 0`, and the
business has sold stock it does not have. A read-modify-write on a counter is
the textbook lost-update anomaly.

**3. No audit trail, so no accountability.** "Who wrote off 40 units of insulin?"
is unanswerable. For a pharmacy this is not merely inconvenient — controlled
substances and expiry-dated goods carry a regulatory duty to show provenance.

## Decision

`stock_movements` is the single source of truth and is **append-only**. Each row
is a signed `qty_delta` against a (product, location, batch) with a reason, an
actor, a timestamp, and an optional reference to the document that caused it.

On-hand stock is **derived**, never stored as an authority:

```
on_hand(product, location, batch) = SUM(qty_delta) over matching movements
```

Three properties follow, and they are the product:

- **Every number is explainable.** Any balance decomposes into the exact rows
  that produced it. "Why is this 11?" always has an answer, on screen, instantly.
- **Corrections are entries, not edits.** A mistake is fixed by posting a
  reversing movement that points at the original via `reversal_of_id`. The
  original stays. History is immutable, so history is trustworthy.
- **Concurrency is a transaction problem with a known solution**, rather than a
  lost update. See ADR 0003.

## Enforcement

A convention that lives only in application code is not a guarantee — the next
developer, a migration script, or a `psql` session will break it. So the
database enforces it directly: a trigger on `stock_movements` **raises** on any
`UPDATE` or `DELETE`. The append-only property is a property of the schema, not
a promise in a README.

This is tested, not asserted: see `src/server/*.integration.test.ts`.

## Consequences

**Accepted costs**

- Reads cost more than reading a column. Mitigated with a covering index and,
  if a real dataset demands it, a materialised view or rolling snapshot rows —
  both are optimisations *on top of* the ledger and neither weakens it.
- The ledger grows without bound. This is fine: movements are small, and a
  business doing 1,000 movements a day takes 20 years to reach 7M rows.
- More write code. Every operation must post a balanced set of movements (a
  transfer is two rows, not one edit), which is more ceremony up front and
  far less debugging later.

**Rejected alternative:** a mutable counter plus a separate audit log. Two
sources of truth that can disagree, and when they disagree you have neither an
accurate count nor a trustworthy log. If the log is authoritative, the counter
is a cache — which is exactly this ADR, with extra steps.

## Prior art

Double-entry bookkeeping, unchanged since the 15th century, for the same reason:
you do not erase a ledger entry, you post a correcting one.
