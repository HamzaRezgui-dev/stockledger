package dev.hamzarezgui.stockledger.ledger;

import dev.hamzarezgui.stockledger.db.BatchEntity;
import dev.hamzarezgui.stockledger.db.BatchRepository;
import dev.hamzarezgui.stockledger.db.LocationEntity;
import dev.hamzarezgui.stockledger.db.LocationRepository;
import dev.hamzarezgui.stockledger.db.ProductEntity;
import dev.hamzarezgui.stockledger.db.ProductRepository;
import dev.hamzarezgui.stockledger.db.StockMovementEntity;
import dev.hamzarezgui.stockledger.db.StockMovementRepository;
import dev.hamzarezgui.stockledger.domain.InsufficientStockException;
import dev.hamzarezgui.stockledger.domain.InvalidMovementException;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundary for ledger writes.
 *
 * <p><strong>This class deliberately carries no retry logic.</strong> A
 * transaction aborted with {@code 40001} is dead — every later statement on it
 * fails — so a retry has to begin a <em>new</em> transaction, which means the
 * retry boundary must sit strictly outside this one. That is
 * {@link LedgerFacade}, a separate bean, because Spring implements both
 * annotations with proxies and a self-invocation inside one class bypasses them
 * entirely.
 *
 * <p>Call {@link LedgerFacade}, not this class, from controllers.
 *
 * @see <a href="file:../../../../../../../docs/adr/0003-serializable-transactions.md">ADR 0003</a>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final StockMovementRepository movements;
    private final ProductRepository products;
    private final LocationRepository locations;
    private final BatchRepository batches;
    private final Clock clock;

    public LedgerService(
            StockMovementRepository movements,
            ProductRepository products,
            LocationRepository locations,
            BatchRepository batches,
            Clock clock) {
        this.movements = movements;
        this.products = products;
        this.locations = locations;
        this.batches = batches;
        this.clock = clock;
    }

    /**
     * Append one movement, refusing anything that would leave the ledger in an
     * impossible state.
     *
     * <p>Runs at {@code SERIALIZABLE} because the work is a read-then-write on a
     * balance that is a {@code SUM} over rows, not a lockable row. Under
     * {@code READ COMMITTED} two concurrent sales of the last unit both observe
     * one in stock and both insert, and stock goes negative. At
     * {@code SERIALIZABLE} Postgres detects the read-write dependency between
     * them and aborts one with {@code 40001}; {@link LedgerFacade} retries it
     * against fresh state.
     *
     * @throws InvalidMovementException    the movement is malformed or references something missing
     * @throws InsufficientStockException  there is not enough stock to take
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public StockMovementEntity post(PostMovementCommand cmd) {
        Qty quantity = require(cmd.quantity(), "quantity");
        UUID productId = require(cmd.productId(), "productId");
        UUID locationId = require(cmd.locationId(), "locationId");

        if (cmd.reason() == null) {
            throw new InvalidMovementException("reason is required");
        }
        if (cmd.actor() == null || cmd.actor().isBlank()) {
            throw new InvalidMovementException("actor is required, so every movement is attributable");
        }

        // Mirrors movements_qty_nonzero and movements_sign_check in the schema.
        if (!cmd.reason().permits(quantity)) {
            throw new InvalidMovementException(
                    "reason %s requires %s, got %s"
                            .formatted(
                                    cmd.reason(),
                                    cmd.reason().expectedSignDescription(),
                                    quantity.toDisplayString()));
        }

        ProductEntity product = products.findById(productId)
                .orElseThrow(() -> new InvalidMovementException("no product with id " + productId));
        if (product.getArchivedAt() != null) {
            throw new InvalidMovementException(
                    "product %s is archived and cannot take new movements".formatted(product.getSku()));
        }

        LocationEntity location = locations.findById(locationId)
                .orElseThrow(() -> new InvalidMovementException("no location with id " + locationId));
        if (location.getArchivedAt() != null) {
            throw new InvalidMovementException(
                    "location %s is archived and cannot take new movements".formatted(location.getCode()));
        }

        UUID batchId = resolveBatch(cmd.batchId(), product);

        // Only an outbound movement can drive a balance below zero, and only a
        // location that has not opted in objects to it.
        if (quantity.isNegative() && !location.isAllowsNegative()) {
            Qty available = balanceOf(productId, locationId, batchId);
            if (available.plus(quantity).isNegative()) {
                throw new InsufficientStockException(productId, locationId, batchId, available, quantity);
            }
        }

        Instant now = clock.instant();
        StockMovementEntity movement = new StockMovementEntity(
                productId,
                locationId,
                batchId,
                quantity,
                cmd.reason(),
                cmd.refType(),
                cmd.refId(),
                cmd.unitCost(),
                cmd.actor().strip(),
                cmd.note(),
                cmd.hasIdempotencyKey() ? cmd.idempotencyKey() : null,
                null,
                // A backdated movement is legitimate (offline capture, a late
                // delivery note); a backdated *recording* is not, or historical
                // reports stop being reproducible.
                cmd.occurredAt() == null ? now : cmd.occurredAt(),
                now);

        StockMovementEntity saved = movements.saveAndFlush(movement);
        log.debug(
                "posted movement {} {} {} product={} location={} batch={}",
                saved.getId(),
                saved.getReason(),
                saved.getQtyDelta().toDisplayString(),
                productId,
                locationId,
                batchId);
        return saved;
    }

    /**
     * Reverse an earlier movement by appending its exact mirror image.
     *
     * <p>The ledger is never edited, so a correction is a new entry that points
     * back at what it corrects (ADR 0001). Note that reversing an inbound
     * movement is itself outbound, so it is subject to the same non-negative
     * check: you cannot un-receive stock that has already been sold.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public StockMovementEntity reverse(Long movementId, String actor, String note) {
        if (actor == null || actor.isBlank()) {
            throw new InvalidMovementException("actor is required, so every reversal is attributable");
        }

        StockMovementEntity original = movements.findById(movementId)
                .orElseThrow(() -> new InvalidMovementException("no movement with id " + movementId));

        if (original.getReversalOfId() != null) {
            throw new InvalidMovementException(
                    "movement %d is itself a reversal; reverse the original (%d) instead"
                            .formatted(movementId, original.getReversalOfId()));
        }
        if (movements.existsByReversalOfId(movementId)) {
            throw new InvalidMovementException(
                    "movement %d has already been reversed".formatted(movementId));
        }

        Qty mirrored = original.getQtyDelta().negated();

        LocationEntity location = locations.findById(original.getLocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "movement %d references missing location %s"
                                .formatted(movementId, original.getLocationId())));

        if (mirrored.isNegative() && !location.isAllowsNegative()) {
            Qty available =
                    balanceOf(original.getProductId(), original.getLocationId(), original.getBatchId());
            if (available.plus(mirrored).isNegative()) {
                throw new InsufficientStockException(
                        original.getProductId(),
                        original.getLocationId(),
                        original.getBatchId(),
                        available,
                        mirrored);
            }
        }

        Instant now = clock.instant();
        StockMovementEntity reversal = new StockMovementEntity(
                original.getProductId(),
                original.getLocationId(),
                original.getBatchId(),
                mirrored,
                // The reason is preserved so the pair reads as one story; the
                // schema's sign check exempts rows with reversal_of_id set.
                original.getReason(),
                original.getRefType(),
                original.getRefId(),
                original.getUnitCost(),
                actor.strip(),
                note,
                null,
                movementId,
                now,
                now);

        StockMovementEntity saved = movements.saveAndFlush(reversal);
        log.info("movement {} reversed by {} (new movement {})", movementId, actor, saved.getId());
        return saved;
    }

    /** The balance of one slot, as an exact {@link Qty}. */
    @Transactional(readOnly = true)
    public Qty balanceOf(UUID productId, UUID locationId, UUID batchId) {
        return Qty.of(movements.balanceOf(productId, locationId, batchId));
    }

    /**
     * Whether a movement for this idempotency key was already written.
     *
     * <p>{@code REQUIRES_NEW} on purpose: {@link LedgerFacade} calls this after a
     * duplicate-key violation, and the transaction that hit the violation is
     * aborted — any further statement on it fails. This must run on a fresh one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<StockMovementEntity> findByIdempotencyKey(String key) {
        return movements.findByIdempotencyKey(key);
    }

    /**
     * Resolves and validates the batch, mirroring
     * {@code stock_movements_validate_batch} in the schema so the caller gets a
     * clean 400 instead of a database error surfacing as a 500.
     */
    private UUID resolveBatch(UUID batchId, ProductEntity product) {
        if (product.isTracksBatches()) {
            if (batchId == null) {
                throw new InvalidMovementException(
                        "product %s tracks batches, so batchId is required".formatted(product.getSku()));
            }
            BatchEntity batch = batches.findById(batchId)
                    .orElseThrow(() -> new InvalidMovementException("no batch with id " + batchId));
            if (!batch.getProductId().equals(product.getId())) {
                throw new InvalidMovementException(
                        "batch %s belongs to a different product".formatted(batchId));
            }
            return batchId;
        }
        if (batchId != null) {
            throw new InvalidMovementException(
                    "product %s does not track batches, so batchId must be omitted"
                            .formatted(product.getSku()));
        }
        return null;
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new InvalidMovementException(field + " is required");
        }
        return value;
    }
}
