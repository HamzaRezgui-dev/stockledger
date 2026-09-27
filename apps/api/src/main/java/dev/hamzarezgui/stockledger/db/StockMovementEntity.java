package dev.hamzarezgui.stockledger.db;

import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.Qty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One row of the append-only ledger.
 *
 * <p>The table blocks {@code UPDATE} and {@code DELETE} with a trigger, so this
 * entity is built so that Hibernate can never attempt either:
 *
 * <ul>
 *   <li>{@code @Immutable} — Hibernate skips dirty checking entirely and will
 *       never issue an {@code UPDATE} for this entity. Without it, a stray
 *       setter call inside a transaction would produce an update at flush time
 *       and hit the trigger as a 500 rather than being prevented outright.
 *   <li>Every column is {@code updatable = false}, so the mapping states the
 *       same constraint a second time, close to each field.
 *   <li>No setters. The only way to build one is the all-args constructor, so a
 *       half-populated movement is not representable.
 * </ul>
 *
 * <p>References to product, location and batch are plain {@link UUID} columns
 * rather than {@code @ManyToOne} associations. A ledger row is an immutable
 * historical fact, not an object graph to navigate: keeping them as ids avoids
 * lazy-loading surprises, keeps inserts to a single statement, and means writing
 * a movement never needs to load the product it refers to. Referential integrity
 * is still enforced — by real foreign keys in the migration.
 *
 * @see <a href="file:../../../../../../../docs/adr/0001-append-only-ledger.md">ADR 0001</a>
 */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "product_id", updatable = false, nullable = false)
    private UUID productId;

    @Column(name = "location_id", updatable = false, nullable = false)
    private UUID locationId;

    /** Null when the product does not track batches. */
    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    /** Signed: positive brings stock in, negative takes it out. Never zero. */
    @Column(name = "qty_delta", updatable = false, nullable = false)
    private Qty qtyDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", updatable = false, nullable = false)
    private MovementReason reason;

    @Column(name = "ref_type", updatable = false)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private String refId;

    /**
     * Kept as {@link BigDecimal} rather than a money type: costing is out of
     * scope for now, and inventing a half-used {@code Money} abstraction would
     * be worse than an honest decimal. The column allows four decimals, since
     * unit costs are routinely finer than the three the ledger uses.
     */
    @Column(name = "unit_cost", updatable = false)
    private BigDecimal unitCost;

    @Column(name = "actor", updatable = false, nullable = false)
    private String actor;

    @Column(name = "note", updatable = false)
    private String note;

    /**
     * Lets a client safely retry a request that timed out. A unique index means
     * the second attempt collapses onto the first movement instead of
     * double-posting — which matters because the write path retries on
     * serialization conflict (ADR 0003).
     */
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    /** Set when this movement reverses an earlier one. Corrections never edit. */
    @Column(name = "reversal_of_id", updatable = false)
    private Long reversalOfId;

    /** When it happened in the warehouse. May be backdated. */
    @Column(name = "occurred_at", updatable = false, nullable = false)
    private Instant occurredAt;

    /** When we were told about it. Never backdated, so reports are reproducible. */
    @Column(name = "recorded_at", updatable = false, nullable = false)
    private Instant recordedAt;

    /** Required by JPA. Not for application use. */
    protected StockMovementEntity() {}

    public StockMovementEntity(
            UUID productId,
            UUID locationId,
            UUID batchId,
            Qty qtyDelta,
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
        this.productId = productId;
        this.locationId = locationId;
        this.batchId = batchId;
        this.qtyDelta = qtyDelta;
        this.reason = reason;
        this.refType = refType;
        this.refId = refId;
        this.unitCost = unitCost;
        this.actor = actor;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
        this.reversalOfId = reversalOfId;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public Qty getQtyDelta() {
        return qtyDelta;
    }

    public MovementReason getReason() {
        return reason;
    }

    public String getRefType() {
        return refType;
    }

    public String getRefId() {
        return refId;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public String getActor() {
        return actor;
    }

    public String getNote() {
        return note;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getReversalOfId() {
        return reversalOfId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
