import { describe, expect, it } from "vitest";
import {
  abs,
  add,
  compare,
  DECIMALS,
  format,
  fromMilli,
  gt,
  lt,
  max,
  min,
  multiplyByInt,
  negate,
  parseQty,
  qty,
  QtyError,
  sub,
  sum,
  toDecimalString,
  toMilli,
  ZERO,
} from "./qty";

describe("qty()", () => {
  it("converts whole units to milli-units", () => {
    expect(toMilli(qty(1))).toBe(1000);
    expect(toMilli(qty(0))).toBe(0);
    expect(toMilli(qty(12.5))).toBe(12_500);
    expect(toMilli(qty(-3))).toBe(-3000);
  });

  it("accepts exactly DECIMALS places, including the smallest unit", () => {
    expect(toMilli(qty(0.001))).toBe(1);
    expect(toMilli(qty(1.234))).toBe(1234);
    expect(toMilli(qty(-0.001))).toBe(-1);
  });

  it("rejects more precision than the ledger can store, rather than rounding", () => {
    // Rounding here would silently destroy stock. Loudly refuse instead.
    expect(() => qty(0.0001)).toThrow(QtyError);
    expect(() => qty(1.2345)).toThrow(/more than 3 decimal places/);
  });

  it("rejects non-finite input", () => {
    expect(() => qty(Number.NaN)).toThrow(QtyError);
    expect(() => qty(Number.POSITIVE_INFINITY)).toThrow(QtyError);
  });

  it("rejects values beyond exact-integer range", () => {
    expect(() => qty(1e15)).toThrow(/out of range/);
  });
});

describe("float-safety (the reason this module exists)", () => {
  it("sums 0.1 + 0.2 to exactly 0.3, which raw floats do not", () => {
    expect(0.1 + 0.2).not.toBe(0.3); // the bug we are avoiding
    expect(add(qty(0.1), qty(0.2))).toBe(qty(0.3));
    expect(format(add(qty(0.1), qty(0.2)))).toBe("0.3");
  });

  it("returns to exactly zero after many fractional movements", () => {
    // 10 receipts of 0.001 then 10 issues of 0.001 must land on a true zero,
    // or a physically-empty bin reports residual stock forever.
    let balance = ZERO;
    for (let i = 0; i < 10; i++) balance = add(balance, qty(0.001));
    for (let i = 0; i < 10; i++) balance = sub(balance, qty(0.001));
    expect(balance).toBe(ZERO);
    expect(format(balance)).toBe("0");
  });

  it("sums a long run of thirds without drift", () => {
    const movements = Array.from({ length: 3000 }, () => qty(0.001));
    expect(sum(movements)).toBe(qty(3));
  });

  it("is order-independent, so replaying a ledger any order gives one answer", () => {
    const a = [qty(1.111), qty(2.222), qty(-0.333), qty(0.001)];
    const b = [qty(0.001), qty(-0.333), qty(2.222), qty(1.111)];
    expect(sum(a)).toBe(sum(b));
  });
});

describe("parseQty()", () => {
  it("parses the decimal strings Postgres numeric returns", () => {
    expect(parseQty("0.000")).toBe(ZERO);
    expect(parseQty("1.000")).toBe(qty(1));
    expect(parseQty("12.500")).toBe(qty(12.5));
    expect(parseQty("-3.250")).toBe(qty(-3.25));
  });

  it("parses shorthand humans type", () => {
    expect(parseQty("7")).toBe(qty(7));
    expect(parseQty("7.5")).toBe(qty(7.5));
    expect(parseQty("+7")).toBe(qty(7));
    expect(parseQty("  7.5  ")).toBe(qty(7.5));
  });

  it("allows trailing zeros beyond DECIMALS, which carry no information", () => {
    expect(parseQty("1.5000")).toBe(qty(1.5));
    expect(parseQty("1.500000")).toBe(qty(1.5));
  });

  it("rejects significant digits beyond DECIMALS", () => {
    expect(() => parseQty("1.5001")).toThrow(/more than 3 decimal places/);
  });

  it("rejects malformed input instead of coercing to NaN", () => {
    for (const bad of ["", "abc", "1.2.3", "1,5", "--1", "1e3", "Infinity"]) {
      expect(() => parseQty(bad), `should reject ${JSON.stringify(bad)}`).toThrow(
        QtyError,
      );
    }
  });

  it("normalises negative zero to zero", () => {
    expect(parseQty("-0.000")).toBe(ZERO);
    expect(Object.is(parseQty("-0.000"), -0)).toBe(false);
  });
});

describe("fromMilli()", () => {
  it("accepts integers", () => {
    expect(fromMilli(1234)).toBe(qty(1.234));
  });

  it("rejects non-integers, which would mean a corrupt stored value", () => {
    expect(() => fromMilli(1.5)).toThrow(QtyError);
  });
});

describe("string round-trips", () => {
  it("toDecimalString always emits DECIMALS places", () => {
    expect(toDecimalString(qty(1))).toBe("1.000");
    expect(toDecimalString(qty(1.5))).toBe("1.500");
    expect(toDecimalString(qty(0.001))).toBe("0.001");
    expect(toDecimalString(qty(-2.25))).toBe("-2.250");
    expect(toDecimalString(ZERO)).toBe("0.000");
  });

  it("survives a parse/serialise round-trip unchanged", () => {
    for (const n of [0, 1, 0.001, 12.5, -3.25, 999.999, -0.001]) {
      const original = qty(n);
      expect(parseQty(toDecimalString(original))).toBe(original);
    }
  });

  it("format() trims noise for humans", () => {
    expect(format(qty(2))).toBe("2");
    expect(format(qty(2.5))).toBe("2.5");
    expect(format(qty(2.05))).toBe("2.05");
    expect(format(ZERO)).toBe("0");
    expect(format(qty(-1.5))).toBe("-1.5");
  });
});

describe("arithmetic", () => {
  it("adds and subtracts exactly", () => {
    expect(add(qty(2), qty(3))).toBe(qty(5));
    expect(sub(qty(5), qty(3))).toBe(qty(2));
    expect(sub(qty(3), qty(5))).toBe(qty(-2));
  });

  it("negates", () => {
    expect(negate(qty(5))).toBe(qty(-5));
    expect(negate(qty(-5))).toBe(qty(5));
    expect(negate(ZERO)).toBe(ZERO);
  });

  it("multiplies by whole factors (12 boxes of 6)", () => {
    expect(multiplyByInt(qty(6), 12)).toBe(qty(72));
    expect(multiplyByInt(qty(0.5), 3)).toBe(qty(1.5));
  });

  it("refuses fractional factors, which would reintroduce float error", () => {
    expect(() => multiplyByInt(qty(6), 1.5)).toThrow(QtyError);
  });

  it("sums an empty ledger to zero", () => {
    expect(sum([])).toBe(ZERO);
  });

  it("never produces negative zero, from any direction", () => {
    // IEEE-754 has two zeros; a ledger has one. A stray -0 formats as
    // "-0.000" and compares unequal to ZERO as a Map key or an assertion.
    const zeros = [
      negate(ZERO),
      abs(ZERO),
      sub(qty(5), qty(5)),
      add(qty(-5), qty(5)),
      sum([qty(1), qty(-1)]),
      multiplyByInt(ZERO, -1),
      multiplyByInt(qty(5), 0),
      qty(-0),
      fromMilli(-0),
      parseQty("-0.000"),
    ];
    for (const z of zeros) {
      expect(Object.is(z, -0)).toBe(false);
      expect(z).toBe(ZERO);
      expect(toDecimalString(z)).toBe("0.000");
      expect(format(z)).toBe("0");
    }
  });

  it("throws on overflow rather than returning a wrong number", () => {
    const huge = fromMilli(Number.MAX_SAFE_INTEGER);
    expect(() => add(huge, qty(1))).toThrow(/overflow/);
  });
});

describe("comparison", () => {
  it("orders correctly", () => {
    expect(gt(qty(2), qty(1))).toBe(true);
    expect(lt(qty(1), qty(2))).toBe(true);
    expect(compare(qty(1), qty(2))).toBeLessThan(0);
    expect(compare(qty(2), qty(1))).toBeGreaterThan(0);
    expect(compare(qty(1), qty(1))).toBe(0);
  });

  it("compares fractions that floats get wrong", () => {
    expect(gt(add(qty(0.1), qty(0.2)), qty(0.3))).toBe(false);
    expect(compare(add(qty(0.1), qty(0.2)), qty(0.3))).toBe(0);
  });

  it("picks min and max", () => {
    expect(min(qty(1), qty(2))).toBe(qty(1));
    expect(max(qty(1), qty(2))).toBe(qty(2));
  });

  it("sorts a list by compare()", () => {
    const sorted = [qty(3), qty(1.5), qty(-2), qty(0)].sort(compare);
    expect(sorted.map(format)).toEqual(["-2", "0", "1.5", "3"]);
  });
});

describe("module invariants", () => {
  it("DECIMALS agrees with SCALE", () => {
    expect(toMilli(qty(1)).toString().length - 1).toBe(DECIMALS);
  });
});
