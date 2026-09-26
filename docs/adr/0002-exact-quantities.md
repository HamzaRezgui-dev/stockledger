# ADR 0002 — Quantities are exact integers, never floats

Status: accepted · 2026-09-26

## Context

Stock quantities are not always whole: 1.5 kg of flour, 0.75 m of cable,
250 ml of solvent. The tempting representation is a JS `number`, i.e. an
IEEE-754 double.

Doubles cannot represent most decimal fractions. `0.1 + 0.2 === 0.30000000000000004`.
In a ledger whose balances are **sums of many rows** (ADR 0001), that error
compounds in two ways that both show up as bugs a client reports:

- A bin that is physically empty reports `0.00000000000000008` on hand, so it
  never appears in "out of stock", is never reordered, and quietly blocks a
  "delete unused product" cleanup.
- A balance of exactly `0.3` compares as *greater than* `0.3`, so an
  availability check passes when it should fail — or fails when it should pass,
  and a cashier cannot sell stock that is visibly on the shelf.

Neither is hypothetical; both are the standard float-in-finance failure, and
inventory is finance with a physical shadow.

## Decision

A quantity is a **branded integer count of milli-units** — three decimal places —
defined in `src/domain/qty.ts`.

```ts
type Qty = number & { readonly [QtyBrand]: true };   // 1.5 kg → 1500
```

- **Integer arithmetic is exact** for magnitudes below 2^53, giving us
  ~9 billion units of headroom. Every `add`/`sub`/`sum` in the module is exact,
  and summing a ledger is order-independent, so replaying movements in any
  order yields one answer.
- **Branded**, so a raw `number` cannot be passed where a `Qty` is expected.
  The compiler blocks the mistake instead of us catching it in review.
- **Three decimals** covers the units real inventories use: pieces (1),
  kilograms (1 g), litres (1 ml), metres (1 mm).

Postgres stores `numeric(14,3)` — exact decimal, agreeing digit-for-digit with
the in-memory representation. `numeric` also means the database's own
`SUM(qty_delta)` is exact, so the derived on-hand view cannot drift from what
the application computes.

## Consequences

**Excess precision is refused, not rounded.** `qty(0.0001)` throws. Rounding
would silently destroy stock, which is the failure this ADR exists to prevent;
a loud error at the boundary is strictly better than a quiet discrepancy in the
ledger. Trailing zeros are accepted (`"1.5000"` parses fine) because they carry
no information.

**Parsing avoids `parseFloat` entirely.** `parseQty` reads the decimal string
with a regex and assembles an integer, so no float touches the value on the way
in from Postgres or from a form field.

**Negative zero is normalised away.** IEEE-754 has two zeros and JS keeps them
distinct under `Object.is`; a ledger has one. Without normalisation,
`negate(ZERO)` yields `-0`, which formats as `"-0.000"` and compares unequal to
`ZERO` as a Map key. Caught by the test suite on first run — see the
`never produces negative zero` case.

**Rejected alternatives**

- `decimal.js` / `big.js` — correct, but a dependency and an allocation per
  operation to solve a problem that fits in an integer. Worth revisiting only
  if we ever need more than 2^53 milli-units or variable precision per product.
- `BigInt` — exact and unbounded, but `pg` returns bigints as strings, it does
  not survive JSON serialisation without custom handling, and it cannot be
  mixed with numbers without explicit conversion. The 2^53 ceiling is not a
  real constraint for inventory, so the ergonomic cost buys nothing.
- Store the unit's smallest division per product (grams for flour, pieces for
  bolts) — pushes a unit-conversion burden onto every call site and makes
  cross-product reporting error-prone.
