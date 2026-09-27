package dev.hamzarezgui.stockledger.db;

import dev.hamzarezgui.stockledger.domain.LocationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Somewhere stock can physically sit. */
@Entity
@Table(name = "locations")
public class LocationEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private LocationKind kind;

    /**
     * Whether this location may hold a negative balance.
     *
     * <p>No setter: turning it on for a real warehouse that already has history
     * would retroactively legitimise phantom stock. A location either is a
     * virtual counterparty from creation or it is not.
     */
    @Column(name = "allows_negative", nullable = false, updatable = false)
    private boolean allowsNegative;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LocationEntity() {}

    public LocationEntity(
            String code, String name, LocationKind kind, boolean allowsNegative, Instant createdAt) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.allowsNegative = allowsNegative;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocationKind getKind() {
        return kind;
    }

    public boolean isAllowsNegative() {
        return allowsNegative;
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
