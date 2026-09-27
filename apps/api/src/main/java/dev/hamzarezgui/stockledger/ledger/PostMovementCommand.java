package dev.hamzarezgui.stockledger.ledger;

import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A request to append one movement to the ledger.
 *
 * <p>{@code quantity} is <strong>signed</strong>, exactly as it will be stored:
 * positive brings stock in, negative takes it out. The alternative — a positive
 * magnitude whose direction the reason supplies — reads more naturally at a call
 * site ("sell 5"), but it means the wire format and the stored format differ,
 * and it needs a second shape for {@code ADJUSTMENT} where the direction is
 * genuinely part of the data. One representation end to end is worth more than
 * the small ergonomic win, and {@link MovementReason#permits} rejects a sign
 * that contradicts the reason with a clear message.
 *
 * <p>A command is not validated by its own construction. Whether a product
 * exists, whether a batch belongs to it, and whether enough stock is on hand are
 * all questions about database state, so they belong in
 * {@link LedgerService#post}, inside the transaction that will act on the answer.
 *
 * @param productId      what moved
 * @param locationId     where it moved
 * @param batchId        which lot; required iff the product tracks batches
 * @param quantity       signed delta, never zero
 * @param reason         why it moved; constrains the sign
 * @param refType        the kind of document behind this, e.g. {@code "PO"}
 * @param refId          that document's identifier
 * @param unitCost       cost per unit, if known
 * @param actor          who did it — required, for the audit trail
 * @param note           free text
 * @param idempotencyKey lets a retried request collapse onto one movement
 * @param occurredAt     when it happened in the warehouse; null means now
 */
public record PostMovementCommand(
        UUID productId,
        UUID locationId,
        UUID batchId,
        Qty quantity,
        MovementReason reason,
        String refType,
        String refId,
        BigDecimal unitCost,
        String actor,
        String note,
        String idempotencyKey,
        Instant occurredAt) {

    /** The common case: move stock, no document reference, no cost. */
    public static PostMovementCommand of(
            UUID productId, UUID locationId, UUID batchId, Qty quantity, MovementReason reason, String actor) {
        return new PostMovementCommand(
                productId, locationId, batchId, quantity, reason,
                null, null, null, actor, null, null, null);
    }

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
