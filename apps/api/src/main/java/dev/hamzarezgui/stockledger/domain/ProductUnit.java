package dev.hamzarezgui.stockledger.domain;

/**
 * The unit a product is counted in.
 *
 * <p>Mirrors {@code products_unit_check} in {@code V1__create_ledger.sql}.
 *
 * <p>All of these are measured at the ledger's three-decimal resolution, which
 * is why the set is deliberately small: {@link Qty} can represent 1 gram,
 * 1 millilitre and 1 millimetre exactly, so every unit here divides cleanly.
 * Adding a unit needing finer resolution means changing {@link Qty#DECIMALS}
 * and the migration together, not just this enum.
 */
public enum ProductUnit {

    /** Discrete items. Fractions are possible but usually a data-entry error. */
    PIECE,

    /** Kilograms; the smallest representable step is 1 gram. */
    KG,

    /** Litres; the smallest representable step is 1 millilitre. */
    LITRE,

    /** Metres; the smallest representable step is 1 millimetre. */
    METRE
}
