package dev.hamzarezgui.stockledger.domain;

/**
 * What kind of place stock is sitting in.
 *
 * <p>Mirrors {@code locations_kind_check} in {@code V1__create_ledger.sql}.
 */
public enum LocationKind {

    /** A stockroom or warehouse. */
    WAREHOUSE,

    /** A shop-floor shelf or bin. */
    SHELF,

    /** A vehicle carrying stock — a technician's van, a delivery run. */
    VAN,

    /** Received but not yet accepted: awaiting inspection, or expired and pending disposal. */
    QUARANTINE,

    /**
     * Not a real place. Used as the counterparty of a movement that would
     * otherwise be one-sided — a supplier, a customer, or shrinkage. These are
     * the locations that legitimately go negative, since stock "in" a supplier
     * is unbounded.
     */
    VIRTUAL
}
