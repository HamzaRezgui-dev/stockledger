package dev.hamzarezgui.stockledger.domain;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An exact stock quantity: an integer count of milli-units (three decimals).
 *
 * <p>Stock arithmetic must never use binary floating point. {@code 0.1 + 0.2}
 * is not {@code 0.3} in a {@code double}, and since a balance in this system is
 * a <em>sum over many ledger rows</em> (ADR 0001) that error compounds: a
 * physically empty bin reports a residue and never appears as out of stock, or
 * an availability check refuses a sale for stock that is visibly on the shelf.
 *
 * <p>Three decimals covers the units real inventories use — pieces (1),
 * kilograms (1 g), litres (1 ml), metres (1 mm) — and matches the database's
 * {@code numeric(14,3)} exactly, including the maximum magnitude.
 *
 * <h2>Why a {@code long} and not a {@code BigDecimal}</h2>
 *
 * {@code BigDecimal} is exact, so it would be correct — but it carries a trap
 * this type is designed to remove. {@code BigDecimal.equals} compares
 * <em>scale</em> as well as value, so {@code 1.5} and {@code 1.500} are not
 * equal, while {@code compareTo} says they are. A value object whose
 * {@code equals} disagrees with its {@code compareTo} is a bug waiting to
 * happen in a {@code HashMap} key, a {@code Set}, or an assertion. Wrapping a
 * {@code long} gives one canonical representation per quantity, so the
 * record's generated {@code equals}, {@code hashCode} and {@code compareTo} all
 * agree by construction.
 *
 * <p>{@code BigDecimal} still appears at the edges, because that is what JDBC
 * exchanges with {@code numeric} — see {@link #toBigDecimal()} and
 * {@link #of(BigDecimal)}.
 *
 * @param milli the quantity as an integer number of thousandths of a unit
 * @see <a href="file:../../../../../../../docs/adr/0002-exact-quantities.md">ADR 0002</a>
 */
public record Qty(long milli) implements Comparable<Qty> {

    /** Decimal places a quantity can represent. Must agree with {@link #SCALE}. */
    public static final int DECIMALS = 3;

    /** Milli-units per whole unit: the ledger's resolution. */
    public static final long SCALE = 1_000L;

    /**
     * Largest magnitude accepted, chosen to equal the database's
     * {@code numeric(14,3)} ceiling (11 integer digits + 3 decimals). Rejecting
     * here rather than at insert turns a database error into a validation error
     * with a useful message.
     */
    public static final long MAX_MILLI = 99_999_999_999_999L;

    public static final Qty ZERO = new Qty(0L);

    /** Deliberately strict: no exponents, no thousands separators, no whitespace inside. */
    private static final Pattern DECIMAL = Pattern.compile("^([+-]?)(\\d{1,11})(?:\\.(\\d+))?$");

    public Qty {
        if (milli < -MAX_MILLI || milli > MAX_MILLI) {
            throw new QtyException(
                    "quantity " + milli + " milli-units is out of range (max " + MAX_MILLI + ")");
        }
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** From an integer number of milli-units — the storage and wire form. */
    public static Qty ofMilli(long milli) {
        return new Qty(milli);
    }

    /** From a whole number of units. */
    public static Qty of(long units) {
        try {
            return new Qty(Math.multiplyExact(units, SCALE));
        } catch (ArithmeticException e) {
            throw new QtyException("quantity " + units + " units is out of range");
        }
    }

    /**
     * From a decimal value — the form JDBC returns for {@code numeric}.
     *
     * <p>Excess precision is <em>refused, not rounded</em>. Rounding would
     * silently destroy stock, which is the failure this whole type exists to
     * prevent; a loud error at the boundary is strictly better than a quiet
     * discrepancy in the ledger. Trailing zeros are fine, since they carry no
     * information: {@code 1.5000} is accepted, {@code 1.5001} is not.
     */
    public static Qty of(BigDecimal units) {
        if (units == null) {
            throw new QtyException("quantity must not be null");
        }
        BigDecimal stripped = units.stripTrailingZeros();
        if (stripped.scale() > DECIMALS) {
            throw new QtyException(
                    "quantity " + units.toPlainString()
                            + " needs more than " + DECIMALS + " decimal places");
        }
        try {
            return new Qty(stripped.movePointRight(DECIMALS).longValueExact());
        } catch (ArithmeticException e) {
            throw new QtyException("quantity " + units.toPlainString() + " is out of range");
        }
    }

    /**
     * Parse a decimal string, as typed by a human or rendered into JSON.
     *
     * <p>Avoids {@code Double.parseDouble} entirely, so no floating point value
     * ever touches the quantity on the way in.
     */
    public static Qty parse(String text) {
        if (text == null) {
            throw new QtyException("quantity must not be null");
        }
        String trimmed = text.trim();
        Matcher m = DECIMAL.matcher(trimmed);
        if (!m.matches()) {
            throw new QtyException("cannot parse quantity from \"" + text + "\"");
        }

        String sign = m.group(1);
        String whole = m.group(2);
        String frac = m.group(3) == null ? "" : m.group(3);

        if (frac.length() > DECIMALS && frac.substring(DECIMALS).chars().anyMatch(c -> c != '0')) {
            throw new QtyException(
                    "quantity " + trimmed + " needs more than " + DECIMALS + " decimal places");
        }

        String padded = frac.length() >= DECIMALS
                ? frac.substring(0, DECIMALS)
                : frac + "0".repeat(DECIMALS - frac.length());

        long magnitude;
        try {
            magnitude = Math.addExact(
                    Math.multiplyExact(Long.parseLong(whole), SCALE),
                    Long.parseLong(padded.isEmpty() ? "0" : padded));
        } catch (ArithmeticException e) {
            throw new QtyException("quantity " + trimmed + " is out of range");
        }

        return new Qty("-".equals(sign) ? -magnitude : magnitude);
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /**
     * Exact decimal value for JDBC and JSON, always at scale {@link #DECIMALS}
     * so it round-trips through {@code numeric(14,3)} unchanged.
     */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(milli, DECIMALS);
    }

    /** Canonical string, always three decimals: {@code "1.500"}. */
    public String toPlainString() {
        return toBigDecimal().toPlainString();
    }

    /** Human-facing string with pointless trailing zeros trimmed: {@code "1.5"}. */
    public String toDisplayString() {
        BigDecimal stripped = toBigDecimal().stripTrailingZeros();
        // stripTrailingZeros turns 0 into 0E-3 and whole numbers into 1E+1.
        return stripped.scale() <= 0
                ? stripped.setScale(0).toPlainString()
                : stripped.toPlainString();
    }

    @Override
    public String toString() {
        return toDisplayString();
    }

    // ------------------------------------------------------------------
    // Arithmetic — exact integer throughout
    // ------------------------------------------------------------------

    public Qty plus(Qty other) {
        try {
            return new Qty(Math.addExact(milli, other.milli));
        } catch (ArithmeticException e) {
            throw new QtyException("quantity overflow adding " + this + " and " + other);
        }
    }

    public Qty minus(Qty other) {
        try {
            return new Qty(Math.subtractExact(milli, other.milli));
        } catch (ArithmeticException e) {
            throw new QtyException("quantity overflow subtracting " + other + " from " + this);
        }
    }

    public Qty negated() {
        return new Qty(-milli);
    }

    public Qty abs() {
        return milli < 0 ? new Qty(-milli) : this;
    }

    /**
     * Scale by a whole factor — twelve boxes of a six-pack.
     *
     * <p>Only integer factors: a fractional multiplier would reintroduce the
     * rounding this type exists to avoid, and the caller should say explicitly
     * what they want instead.
     */
    public Qty times(long factor) {
        try {
            return new Qty(Math.multiplyExact(milli, factor));
        } catch (ArithmeticException e) {
            throw new QtyException("quantity overflow multiplying " + this + " by " + factor);
        }
    }

    /** Exact sum. Integer addition, so the result never depends on ordering. */
    public static Qty sum(Iterable<Qty> quantities) {
        Qty total = ZERO;
        for (Qty q : quantities) {
            total = total.plus(q);
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Predicates and ordering
    // ------------------------------------------------------------------

    public boolean isZero() {
        return milli == 0L;
    }

    public boolean isPositive() {
        return milli > 0L;
    }

    public boolean isNegative() {
        return milli < 0L;
    }

    public boolean isGreaterThan(Qty other) {
        return milli > other.milli;
    }

    public boolean isGreaterThanOrEqual(Qty other) {
        return milli >= other.milli;
    }

    public boolean isLessThan(Qty other) {
        return milli < other.milli;
    }

    public boolean isLessThanOrEqual(Qty other) {
        return milli <= other.milli;
    }

    public static Qty min(Qty a, Qty b) {
        return a.milli <= b.milli ? a : b;
    }

    public static Qty max(Qty a, Qty b) {
        return a.milli >= b.milli ? a : b;
    }

    @Override
    public int compareTo(Qty other) {
        return Long.compare(milli, other.milli);
    }
}
