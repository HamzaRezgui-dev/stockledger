package dev.hamzarezgui.stockledger.query;

import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.ProductUnit;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read models.
 *
 * <p>These are plain records shaped for a screen, not entities. Reads go through
 * {@link StockQueryService} and {@code JdbcClient} with hand-written SQL rather
 * than JPA, for two reasons: the interesting queries read from
 * <em>views</em> and use window functions that no ORM expresses well, and a
 * report has no identity, no lifecycle and nothing to dirty-check, so an entity
 * would only add overhead and lazy-loading hazards.
 *
 * <p>Writes still go through JPA, where the entity lifecycle and transaction
 * management earn their keep. Splitting the two is worth the small duplication.
 */
public final class StockViews {

    private StockViews() {}

    /**
     * One slot of stock: a product, at a location, optionally in a batch.
     *
     * @param qty derived from the ledger, never stored — see ADR 0001
     */
    public record OnHand(
            UUID productId,
            String sku,
            String productName,
            ProductUnit unit,
            UUID locationId,
            String locationCode,
            UUID batchId,
            String lotCode,
            LocalDate expiresOn,
            Qty qty,
            Instant lastMovementAt) {}

    /**
     * One ledger entry, with the balance as it stood immediately after.
     *
     * <p>{@code balanceAfter} is what makes a ledger worth having: it turns
     * "your stock is 11" into a line-by-line account of how it got there, so
     * "why is this 11?" always has an answer on screen.
     */
    public record MovementLine(
            long id,
            Instant occurredAt,
            Instant recordedAt,
            MovementReason reason,
            Qty qtyDelta,
            Qty balanceAfter,
            String actor,
            String note,
            String refType,
            String refId,
            UUID locationId,
            String locationCode,
            UUID batchId,
            String lotCode,
            BigDecimal unitCost,
            Long reversalOfId,
            boolean reversed) {

        /** True when this line is itself a correction of an earlier one. */
        public boolean isReversal() {
            return reversalOfId != null;
        }
    }

    /** A product whose total on-hand has fallen to or below its reorder point. */
    public record LowStock(
            UUID productId,
            String sku,
            String productName,
            ProductUnit unit,
            Qty onHand,
            Qty reorderPoint,
            Qty shortfall) {}

    /**
     * A batch approaching or past its expiry while still holding stock.
     *
     * @param daysRemaining negative once expired, which is how the UI sorts the
     *     genuinely urgent rows to the top
     */
    public record NearExpiry(
            UUID productId,
            String sku,
            String productName,
            UUID locationId,
            String locationCode,
            UUID batchId,
            String lotCode,
            LocalDate expiresOn,
            long daysRemaining,
            Qty qty) {

        public boolean isExpired() {
            return daysRemaining < 0;
        }
    }
}
