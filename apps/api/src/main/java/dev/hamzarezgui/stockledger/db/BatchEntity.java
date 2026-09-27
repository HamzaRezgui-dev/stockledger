package dev.hamzarezgui.stockledger.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A lot of a product, optionally with an expiry date.
 *
 * <p>{@code expiresOn} is a {@link LocalDate}, not an {@link Instant}. An expiry
 * is a calendar fact printed on a box, and it is the same day everywhere in the
 * world. Storing it as an instant forces an arbitrary time-of-day and timezone
 * choice, and then stock expires a day early or a day late depending on the
 * server's offset — a bug that only shows up in production, near midnight, for
 * some users.
 *
 * <p>Nullable: not everything expires. A null here means "no expiry tracked",
 * and FEFO allocation sorts such lots last, because dated stock is the stock at
 * risk of being written off.
 */
@Entity
@Table(name = "batches")
public class BatchEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "lot_code", nullable = false, updatable = false)
    private String lotCode;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BatchEntity() {}

    public BatchEntity(UUID productId, String lotCode, LocalDate expiresOn, Instant createdAt) {
        this.productId = productId;
        this.lotCode = lotCode;
        this.expiresOn = expiresOn;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getLotCode() {
        return lotCode;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    /** Correcting a mistyped expiry is legitimate; the printed box is the truth. */
    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** True when this lot is expired as of the given day. Null expiry never expires. */
    public boolean isExpiredAsOf(LocalDate asOf) {
        return expiresOn != null && expiresOn.isBefore(asOf);
    }
}
