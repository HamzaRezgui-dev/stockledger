package dev.hamzarezgui.stockledger.db;

import dev.hamzarezgui.stockledger.domain.ProductUnit;
import dev.hamzarezgui.stockledger.domain.Qty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A thing we hold stock of.
 *
 * <p>Unlike {@link StockMovementEntity} this is ordinary mutable reference data:
 * a product gets renamed, its reorder point gets tuned, it eventually gets
 * archived. Only the <em>ledger</em> is append-only.
 *
 * <p>Archiving rather than deleting: a product referenced by historical
 * movements must keep existing, or the ledger stops being explainable. The
 * foreign key would refuse the delete anyway; {@code archivedAt} makes the
 * intent explicit and keeps it out of pickers.
 */
@Entity
@Table(name = "products")
public class ProductEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sku", nullable = false)
    private String sku;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Nullable, and unique when present — Postgres allows many NULLs in a unique
     * index, so unbarcoded products coexist while real barcodes stay unique.
     */
    @Column(name = "barcode")
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false)
    private ProductUnit unit;

    /**
     * Whether movements of this product must name a batch. Immutable after
     * creation in practice: flipping it would orphan the batch data already
     * recorded against existing movements, so there is deliberately no setter.
     */
    @Column(name = "tracks_batches", nullable = false, updatable = false)
    private boolean tracksBatches;

    @Column(name = "reorder_point", nullable = false)
    private Qty reorderPoint;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductEntity() {}

    public ProductEntity(
            String sku,
            String name,
            String barcode,
            ProductUnit unit,
            boolean tracksBatches,
            Qty reorderPoint,
            Instant createdAt) {
        this.sku = sku;
        this.name = name;
        this.barcode = barcode;
        this.unit = unit;
        this.tracksBatches = tracksBatches;
        this.reorderPoint = reorderPoint;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public ProductUnit getUnit() {
        return unit;
    }

    public boolean isTracksBatches() {
        return tracksBatches;
    }

    public Qty getReorderPoint() {
        return reorderPoint;
    }

    public void setReorderPoint(Qty reorderPoint) {
        this.reorderPoint = reorderPoint;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void archive(Instant at) {
        this.archivedAt = at;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
