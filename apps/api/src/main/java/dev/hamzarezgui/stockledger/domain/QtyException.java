package dev.hamzarezgui.stockledger.domain;

/**
 * A quantity could not be represented exactly, or was out of range.
 *
 * <p>Extends {@link IllegalArgumentException} because every cause is bad input —
 * excess precision, an unparseable string, a magnitude beyond the ledger's
 * {@code numeric(14,3)} column — which lets the web layer map it to a 400
 * without a dedicated handler.
 *
 * <p>Deliberately not a checked exception: a {@code Qty} is constructed at
 * request boundaries and in arithmetic that cannot meaningfully recover, so
 * forcing every caller to catch would add noise without adding safety.
 */
public class QtyException extends IllegalArgumentException {

    public QtyException(String message) {
        super(message);
    }
}
