package dev.hamzarezgui.stockledger.db;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementRepository extends JpaRepository<StockMovementEntity, Long> {

    /**
     * The balance for one product/location/batch slot.
     *
     * <p>A native query for one specific reason: {@code is not distinct from}.
     * A batch slot is identified by a possibly-null {@code batch_id}, and in SQL
     * {@code batch_id = NULL} is never true — so the obvious JPQL {@code =}
     * would silently match nothing for non-batched products and report every
     * balance as zero. That failure mode is quiet and catastrophic: every
     * availability check would pass.
     *
     * <p>The {@code cast(... as uuid)} is required because Postgres cannot infer
     * a bound parameter's type when it appears only next to {@code is not
     * distinct from} with a NULL value.
     *
     * <p>Called inside the SERIALIZABLE transaction in {@code LedgerService}.
     * The read is what gives Postgres's Serializable Snapshot Isolation the
     * predicate to track, so two concurrent issues of the same last unit
     * conflict and one aborts — see ADR 0003.
     */
    @Query(
            value =
                    """
                    select coalesce(sum(qty_delta), 0)
                    from stock_movements
                    where product_id = cast(:productId as uuid)
                      and location_id = cast(:locationId as uuid)
                      and batch_id is not distinct from cast(:batchId as uuid)
                    """,
            nativeQuery = true)
    BigDecimal balanceOf(
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("batchId") UUID batchId);

    /**
     * Finds the movement a previous attempt already wrote for this key, so a
     * retried request returns the original instead of double-posting.
     */
    Optional<StockMovementEntity> findByIdempotencyKey(String idempotencyKey);

    /** True when this movement has already been reversed; a second reversal would double-count. */
    boolean existsByReversalOfId(Long reversalOfId);

    /**
     * The movements behind a balance, newest first — the "explain this number"
     * query that is the whole point of a ledger (ADR 0001).
     */
    @Query(
            """
            select m from StockMovementEntity m
            where m.productId = :productId
              and (:locationId is null or m.locationId = :locationId)
            order by m.occurredAt desc, m.id desc
            """)
    List<StockMovementEntity> history(
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            Pageable pageable);
}
