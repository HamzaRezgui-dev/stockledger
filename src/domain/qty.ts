/**
 * Exact quantities.
 *
 * Stock arithmetic must never use IEEE-754 floats. `0.1 + 0.2 !== 0.3` is a
 * curiosity in a blog post and a stock discrepancy in a warehouse: sum enough
 * float deltas and an item that is physically present reports as 0.0000001,
 * or a "zero" balance blocks a legitimate sale.
 *
 * So a Qty is an integer count of milli-units (3 decimal places), carried as a
 * branded `number`. Integers up to 2^53 are exact in JS, which caps us at
 * ~9.007e12 milli-units = 9 billion units. No warehouse reaches that, and
 * every add/subtract in this module is exact integer arithmetic.
 *
 * 3 decimals is the resolution of the ledger itself, chosen to cover the
 * units real inventories use: pieces (1), kilograms (0.001 = 1 gram),
 * litres (0.001 = 1 ml), metres (0.001 = 1 mm).
 *
 * See docs/adr/0002-exact-quantities.md
 */

declare const QtyBrand: unique symbol;

/** An exact quantity: an integer number of milli-units. Construct via `qty()`. */
export type Qty = number & { readonly [QtyBrand]: true };

/** Milli-units per whole unit. The ledger's resolution. */
export const SCALE = 1000;

/** Decimal places representable by a Qty. Must agree with SCALE. */
export const DECIMALS = 3;

/** Largest magnitude we accept, keeping all arithmetic inside exact-integer range. */
const MAX_MILLI = Number.MAX_SAFE_INTEGER;

export class QtyError extends Error {
  override readonly name = "QtyError";
}

/** The zero quantity. */
export const ZERO = 0 as Qty;

/**
 * Build a Qty from a whole-unit number.
 *
 * Rejects values needing more than DECIMALS places rather than rounding them:
 * silently dropping a digit is exactly the data loss this module exists to
 * prevent. Callers who genuinely want rounding must ask for it explicitly.
 */
export function qty(units: number): Qty {
  if (!Number.isFinite(units)) {
    throw new QtyError(`quantity must be finite, got ${units}`);
  }
  const milli = units * SCALE;
  // Tolerate float representation error in the *input* literal (0.001 * 1000
  // is 1.0000000000000002), but reject genuinely-too-precise values.
  const rounded = Math.round(milli);
  if (Math.abs(milli - rounded) > 1e-6) {
    throw new QtyError(
      `quantity ${units} needs more than ${DECIMALS} decimal places`,
    );
  }
  if (Math.abs(rounded) > MAX_MILLI) {
    throw new QtyError(`quantity ${units} is out of range`);
  }
  return rounded as Qty;
}

/** Build a Qty directly from a milli-unit integer (the DB and wire form). */
export function fromMilli(milli: number): Qty {
  if (!Number.isInteger(milli)) {
    throw new QtyError(`milli-units must be an integer, got ${milli}`);
  }
  if (Math.abs(milli) > MAX_MILLI) {
    throw new QtyError(`quantity ${milli} milli-units is out of range`);
  }
  return milli as Qty;
}

/**
 * Parse a decimal string — the form Postgres `numeric` returns, and the form
 * humans type. Avoids `parseFloat` so no float ever touches the value.
 */
export function parseQty(text: string): Qty {
  const trimmed = text.trim();
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(trimmed);
  if (!match) {
    throw new QtyError(`cannot parse quantity from ${JSON.stringify(text)}`);
  }
  const [, sign, whole = "0", frac = ""] = match;
  if (frac.length > DECIMALS) {
    // Trailing zeros carry no information, so "1.5000" is fine; "1.5001" is not.
    const excess = frac.slice(DECIMALS);
    if (/[^0]/.test(excess)) {
      throw new QtyError(
        `quantity ${trimmed} needs more than ${DECIMALS} decimal places`,
      );
    }
  }
  const padded = frac.padEnd(DECIMALS, "0").slice(0, DECIMALS);
  const magnitude = Number(whole) * SCALE + Number(padded || "0");
  if (magnitude > MAX_MILLI) {
    throw new QtyError(`quantity ${trimmed} is out of range`);
  }
  return ((sign === "-" ? -magnitude : magnitude) || 0) as Qty;
}

/** Milli-unit integer, for storage and transport. */
export function toMilli(q: Qty): number {
  return q;
}

/**
 * Canonical decimal string: always DECIMALS places, so it round-trips through
 * `parseQty` and compares correctly as a Postgres `numeric(_, 3)`.
 */
export function toDecimalString(q: Qty): string {
  const negative = q < 0;
  const magnitude = Math.abs(q);
  const whole = Math.trunc(magnitude / SCALE);
  const frac = (magnitude % SCALE).toString().padStart(DECIMALS, "0");
  return `${negative ? "-" : ""}${whole}.${frac}`;
}

/** Human-facing string: trims pointless trailing zeros ("2" not "2.000"). */
export function format(q: Qty): string {
  const s = toDecimalString(q);
  return s.includes(".") ? s.replace(/\.?0+$/, "") : s;
}

function guard(milli: number): Qty {
  if (Math.abs(milli) > MAX_MILLI) {
    throw new QtyError("quantity overflow");
  }
  return milli as Qty;
}

export function add(a: Qty, b: Qty): Qty {
  return guard(a + b);
}

export function sub(a: Qty, b: Qty): Qty {
  return guard(a - b);
}

export function negate(q: Qty): Qty {
  return -q as Qty;
}

export function abs(q: Qty): Qty {
  return Math.abs(q) as Qty;
}

/** Exact sum of many quantities. Integer addition, so order never matters. */
export function sum(quantities: readonly Qty[]): Qty {
  let total = 0;
  for (const q of quantities) total += q;
  return guard(total);
}

/** Scale by a whole number of times (e.g. 12 boxes of a 6-pack). */
export function multiplyByInt(q: Qty, factor: number): Qty {
  if (!Number.isInteger(factor)) {
    throw new QtyError(`factor must be an integer, got ${factor}`);
  }
  return guard(q * factor);
}

export function isZero(q: Qty): boolean {
  return q === 0;
}

export function isPositive(q: Qty): boolean {
  return q > 0;
}

export function isNegative(q: Qty): boolean {
  return q < 0;
}

/** Negative when a < b, zero when equal, positive when a > b. */
export function compare(a: Qty, b: Qty): number {
  return a - b;
}

export function equals(a: Qty, b: Qty): boolean {
  return a === b;
}

export function gt(a: Qty, b: Qty): boolean {
  return a > b;
}

export function gte(a: Qty, b: Qty): boolean {
  return a >= b;
}

export function lt(a: Qty, b: Qty): boolean {
  return a < b;
}

export function lte(a: Qty, b: Qty): boolean {
  return a <= b;
}

export function min(a: Qty, b: Qty): Qty {
  return a <= b ? a : b;
}

export function max(a: Qty, b: Qty): Qty {
  return a >= b ? a : b;
}
