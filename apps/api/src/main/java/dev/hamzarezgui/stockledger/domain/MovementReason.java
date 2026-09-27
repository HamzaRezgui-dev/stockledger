package dev.hamzarezgui.stockledger.domain;

/**
 * Why stock moved.
 *
 * <p>Each reason carries the direction it is allowed to move stock in, which
 * makes a whole class of bug impossible to express: a {@code SALE} can never
 * secretly increase stock, and a {@code RECEIPT} can never decrease it.
 *
 * <p><strong>This enum is mirrored by the {@code movements_reason_check} and
 * {@code movements_sign_check} constraints in {@code V1__create_ledger.sql}.</strong>
 * The two must agree, so {@code LedgerSchemaIntegrationTest} reads the live
 * constraint out of {@code pg_constraint} and fails if they ever drift — adding
 * a reason here without a migration is caught by the build, not by a user.
 */
public enum MovementReason {

    /** Goods arrived from a supplier. */
    RECEIPT(Direction.IN),

    /** A customer returned stock to us. */
    RETURN_IN(Direction.IN),

    /** Arrived from another of our own locations. */
    TRANSFER_IN(Direction.IN),

    /** Goods left to a customer. */
    SALE(Direction.OUT),

    /** Damaged, expired or stolen. */
    WRITE_OFF(Direction.OUT),

    /** Left for another of our own locations. */
    TRANSFER_OUT(Direction.OUT),

    /** A deliberate manual correction, in either direction. */
    ADJUSTMENT(Direction.EITHER),

    /** A physical count disagreed with the ledger, in either direction. */
    COUNT_CORRECTION(Direction.EITHER);

    /** Which way a reason is permitted to move stock. */
    public enum Direction {
        IN,
        OUT,
        EITHER
    }

    private final Direction direction;

    MovementReason(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }

    /** True when this reason always increases stock. */
    public boolean isInbound() {
        return direction == Direction.IN;
    }

    /** True when this reason always decreases stock. */
    public boolean isOutbound() {
        return direction == Direction.OUT;
    }

    /**
     * Whether {@code delta} is a legal quantity for this reason.
     *
     * <p>Zero is never legal: a movement that moves nothing is a bug, not a
     * record. Mirrors {@code movements_qty_nonzero} and
     * {@code movements_sign_check}.
     */
    public boolean permits(Qty delta) {
        if (delta.isZero()) {
            return false;
        }
        return switch (direction) {
            case IN -> delta.isPositive();
            case OUT -> delta.isNegative();
            case EITHER -> true;
        };
    }

    /**
     * A human-readable explanation of what {@link #permits} requires, used to
     * build a useful 400 rather than a bare rejection.
     */
    public String expectedSignDescription() {
        return switch (direction) {
            case IN -> "a positive quantity";
            case OUT -> "a negative quantity";
            case EITHER -> "a non-zero quantity";
        };
    }
}
