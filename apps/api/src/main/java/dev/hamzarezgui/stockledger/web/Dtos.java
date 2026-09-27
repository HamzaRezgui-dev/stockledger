package dev.hamzarezgui.stockledger.web;

import dev.hamzarezgui.stockledger.db.BatchEntity;
import dev.hamzarezgui.stockledger.db.LocationEntity;
import dev.hamzarezgui.stockledger.db.ProductEntity;
import dev.hamzarezgui.stockledger.db.StockMovementEntity;
import dev.hamzarezgui.stockledger.domain.LocationKind;
import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.ProductUnit;
import dev.hamzarezgui.stockledger.domain.Qty;
import dev.hamzarezgui.stockledger.query.StockViews;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The HTTP contract.
 *
 * <p>Separate from the entities on purpose. Serialising entities straight out
 * couples the wire format to the schema, so a column rename becomes a breaking
 * API change, and it invites lazy-loading during serialisation. These records are
 * also the layer that decides what clients are allowed to <em>send</em>, which is
 * not the same set of fields they may read.
 *
 * <h2>Quantities cross the wire as strings</h2>
 *
 * Every quantity is a {@code String} like {@code "12.500"}, not a JSON number.
 * JSON numbers are parsed as IEEE-754 doubles by most clients — including
 * {@code JSON.parse} in the browser — which would reintroduce exactly the
 * rounding error {@link Qty} exists to eliminate, at the last possible moment.
 * A string survives the round trip exactly. See ADR 0002.
 */
public final class Dtos {

    private Dtos() {}

    /** Matches an exact decimal with at most three places; mirrors {@link Qty}. */
    private static final String QTY_PATTERN = "^[+-]?\\d{1,11}(\\.\\d{1,3})?$";

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    /**
     * Post one movement.
     *
     * @param quantity signed, as stored: positive in, negative out. The reason
     *     constrains the sign, and a mismatch is a 400 rather than a silent flip.
     */
    public record PostMovementRequest(
            @NotNull(message = "productId is required") UUID productId,
            @NotNull(message = "locationId is required") UUID locationId,
            UUID batchId,
            @NotBlank(message = "quantity is required")
                    @Pattern(
                            regexp = QTY_PATTERN,
                            message = "quantity must be an exact decimal with at most 3 places, e.g. \"12.500\"")
                    String quantity,
            @NotNull(message = "reason is required") MovementReason reason,
            @Size(max = 32) String refType,
            @Size(max = 128) String refId,
            @PositiveOrZero(message = "unitCost cannot be negative") BigDecimal unitCost,
            @Size(max = 1000) String note,
            /**
             * When it happened in the warehouse. Null means now. Accepting this
             * lets an offline client post what it captured earlier without
             * pretending it happened at upload time.
             */
            Instant occurredAt) {

        public Qty parsedQuantity() {
            return Qty.parse(quantity);
        }
    }

    /** Reverse a movement. The quantity is not a parameter: a reversal is the exact mirror. */
    public record ReverseMovementRequest(@Size(max = 1000) String note) {}

    public record CreateProductRequest(
            @NotBlank(message = "sku is required") @Size(max = 64) String sku,
            @NotBlank(message = "name is required") @Size(max = 200) String name,
            @Size(max = 64) String barcode,
            @NotNull(message = "unit is required") ProductUnit unit,
            /**
             * Whether movements must name a batch. Not changeable afterwards:
             * flipping it would orphan batch data already recorded against
             * existing movements.
             */
            boolean tracksBatches,
            @Pattern(regexp = QTY_PATTERN, message = "reorderPoint must be an exact decimal")
                    String reorderPoint) {

        public Qty parsedReorderPoint() {
            return reorderPoint == null || reorderPoint.isBlank() ? Qty.ZERO : Qty.parse(reorderPoint);
        }
    }

    public record CreateLocationRequest(
            @NotBlank(message = "code is required") @Size(max = 32) String code,
            @NotBlank(message = "name is required") @Size(max = 200) String name,
            @NotNull(message = "kind is required") LocationKind kind,
            /**
             * Whether this location may hold a negative balance. Only meaningful
             * for VIRTUAL counterparties such as a supplier; setting it on a real
             * warehouse disables the protection that stops phantom stock.
             */
            boolean allowsNegative) {}

    public record CreateBatchRequest(
            @NotNull(message = "productId is required") UUID productId,
            @NotBlank(message = "lotCode is required") @Size(max = 64) String lotCode,
            /** Null when this lot has no expiry. A calendar date, never an instant. */
            LocalDate expiresOn) {}

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    public record MovementResponse(
            long id,
            UUID productId,
            UUID locationId,
            UUID batchId,
            String quantity,
            MovementReason reason,
            String refType,
            String refId,
            BigDecimal unitCost,
            String actor,
            String note,
            String idempotencyKey,
            Long reversalOfId,
            Instant occurredAt,
            Instant recordedAt) {

        public static MovementResponse from(StockMovementEntity m) {
            return new MovementResponse(
                    m.getId(),
                    m.getProductId(),
                    m.getLocationId(),
                    m.getBatchId(),
                    m.getQtyDelta().toPlainString(),
                    m.getReason(),
                    m.getRefType(),
                    m.getRefId(),
                    m.getUnitCost(),
                    m.getActor(),
                    m.getNote(),
                    m.getIdempotencyKey(),
                    m.getReversalOfId(),
                    m.getOccurredAt(),
                    m.getRecordedAt());
        }
    }

    public record ProductResponse(
            UUID id,
            String sku,
            String name,
            String barcode,
            ProductUnit unit,
            boolean tracksBatches,
            String reorderPoint,
            Instant archivedAt) {

        public static ProductResponse from(ProductEntity p) {
            return new ProductResponse(
                    p.getId(),
                    p.getSku(),
                    p.getName(),
                    p.getBarcode(),
                    p.getUnit(),
                    p.isTracksBatches(),
                    p.getReorderPoint().toPlainString(),
                    p.getArchivedAt());
        }
    }

    public record LocationResponse(
            UUID id,
            String code,
            String name,
            LocationKind kind,
            boolean allowsNegative,
            Instant archivedAt) {

        public static LocationResponse from(LocationEntity l) {
            return new LocationResponse(
                    l.getId(), l.getCode(), l.getName(), l.getKind(), l.isAllowsNegative(), l.getArchivedAt());
        }
    }

    public record BatchResponse(UUID id, UUID productId, String lotCode, LocalDate expiresOn) {

        public static BatchResponse from(BatchEntity b) {
            return new BatchResponse(b.getId(), b.getProductId(), b.getLotCode(), b.getExpiresOn());
        }
    }

    public record OnHandResponse(
            UUID productId,
            String sku,
            String productName,
            ProductUnit unit,
            UUID locationId,
            String locationCode,
            UUID batchId,
            String lotCode,
            LocalDate expiresOn,
            String qty,
            Instant lastMovementAt) {

        public static OnHandResponse from(StockViews.OnHand r) {
            return new OnHandResponse(
                    r.productId(),
                    r.sku(),
                    r.productName(),
                    r.unit(),
                    r.locationId(),
                    r.locationCode(),
                    r.batchId(),
                    r.lotCode(),
                    r.expiresOn(),
                    r.qty().toPlainString(),
                    r.lastMovementAt());
        }
    }

    /** One ledger line, with the balance as it stood after it. */
    public record MovementLineResponse(
            long id,
            Instant occurredAt,
            Instant recordedAt,
            MovementReason reason,
            String qtyDelta,
            String balanceAfter,
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

        public static MovementLineResponse from(StockViews.MovementLine l) {
            return new MovementLineResponse(
                    l.id(),
                    l.occurredAt(),
                    l.recordedAt(),
                    l.reason(),
                    l.qtyDelta().toPlainString(),
                    l.balanceAfter().toPlainString(),
                    l.actor(),
                    l.note(),
                    l.refType(),
                    l.refId(),
                    l.locationId(),
                    l.locationCode(),
                    l.batchId(),
                    l.lotCode(),
                    l.unitCost(),
                    l.reversalOfId(),
                    l.reversed());
        }
    }

    public record LowStockResponse(
            UUID productId,
            String sku,
            String productName,
            ProductUnit unit,
            String onHand,
            String reorderPoint,
            String shortfall) {

        public static LowStockResponse from(StockViews.LowStock r) {
            return new LowStockResponse(
                    r.productId(),
                    r.sku(),
                    r.productName(),
                    r.unit(),
                    r.onHand().toPlainString(),
                    r.reorderPoint().toPlainString(),
                    r.shortfall().toPlainString());
        }
    }

    public record NearExpiryResponse(
            UUID productId,
            String sku,
            String productName,
            UUID locationId,
            String locationCode,
            UUID batchId,
            String lotCode,
            LocalDate expiresOn,
            long daysRemaining,
            boolean expired,
            String qty) {

        public static NearExpiryResponse from(StockViews.NearExpiry r) {
            return new NearExpiryResponse(
                    r.productId(),
                    r.sku(),
                    r.productName(),
                    r.locationId(),
                    r.locationCode(),
                    r.batchId(),
                    r.lotCode(),
                    r.expiresOn(),
                    r.daysRemaining(),
                    r.isExpired(),
                    r.qty().toPlainString());
        }
    }

    /**
     * A machine-readable error.
     *
     * @param code a stable symbol the client can branch on, so behaviour never
     *     depends on parsing the human-readable message
     * @param details extra structured context, e.g. available vs requested stock
     */
    public record ErrorResponse(String code, String message, Object details) {

        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, null);
        }
    }
}
