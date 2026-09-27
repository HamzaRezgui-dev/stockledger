package dev.hamzarezgui.stockledger.domain;

import java.util.UUID;

/**
 * An outbound movement would have driven a balance below zero.
 *
 * <p>Carries the numbers rather than only a message, so a caller can render
 * "you have 3, you asked for 5" without parsing prose, and a UI can offer to
 * post the available quantity instead.
 */
public class InsufficientStockException extends LedgerException {

    private final UUID productId;
    private final UUID locationId;
    private final UUID batchId;
    private final Qty available;
    private final Qty requested;

    public InsufficientStockException(
            UUID productId, UUID locationId, UUID batchId, Qty available, Qty requested) {
        super("insufficient stock for product " + productId + " at location " + locationId
                + (batchId == null ? "" : " batch " + batchId)
                + ": available " + available.toDisplayString()
                + ", requested " + requested.abs().toDisplayString());
        this.productId = productId;
        this.locationId = locationId;
        this.batchId = batchId;
        this.available = available;
        this.requested = requested;
    }

    public UUID productId() {
        return productId;
    }

    public UUID locationId() {
        return locationId;
    }

    /** Null when the product does not track batches. */
    public UUID batchId() {
        return batchId;
    }

    /** What was actually on hand. */
    public Qty available() {
        return available;
    }

    /** The signed delta that was refused. */
    public Qty requested() {
        return requested;
    }

    /** How much was missing — the amount a partial fill would have to drop. */
    public Qty shortfall() {
        return requested.abs().minus(available);
    }
}
