package dev.hamzarezgui.stockledger.query;

import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.ProductUnit;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read queries over the ledger's derived views.
 *
 * <p>All reads run at the default {@code READ COMMITTED}, deliberately. Reports
 * do not need serializability, and paying for it on every dashboard query would
 * add contention for no correctness gain — the invariants that matter are
 * enforced on the write path (ADR 0003).
 */
@Service
@Transactional(readOnly = true)
public class StockQueryService {

    private final JdbcClient db;

    public StockQueryService(JdbcClient db) {
        this.db = db;
    }

    /**
     * Current stock, optionally narrowed to one product or location.
     *
     * <p>Zero balances are filtered out: a slot that once held stock and no
     * longer does is noise on a stock list, and its history is still reachable
     * through {@link #history}. Negative balances are <em>not</em> filtered —
     * they should be impossible outside a location that opted in, so if one
     * appears, it must be visible rather than hidden.
     */
    public List<StockViews.OnHand> onHand(UUID productId, UUID locationId) {
        return db.sql(
                        """
                        select p.id            as product_id,
                               p.sku           as sku,
                               p.name          as product_name,
                               p.unit          as unit,
                               l.id            as location_id,
                               l.code          as location_code,
                               b.id            as batch_id,
                               b.lot_code      as lot_code,
                               b.expires_on    as expires_on,
                               soh.qty         as qty,
                               soh.last_movement_at as last_movement_at
                        from stock_on_hand soh
                            join products  p on p.id = soh.product_id
                            join locations l on l.id = soh.location_id
                            left join batches b on b.id = soh.batch_id
                        where soh.qty <> 0
                          and (cast(:productId as uuid) is null or p.id = cast(:productId as uuid))
                          and (cast(:locationId as uuid) is null or l.id = cast(:locationId as uuid))
                        order by p.name, l.code, b.expires_on nulls last
                        """)
                .param("productId", productId)
                .param("locationId", locationId)
                .query(StockQueryService::mapOnHand)
                .list();
    }

    /**
     * The movements behind a product's balance, newest first, each carrying the
     * balance as it stood immediately after it.
     *
     * <p>The running total is a window function over the ledger ordered the way
     * the balance actually accumulated ({@code occurred_at}, then {@code id} to
     * break ties deterministically). Computing it in SQL rather than in Java
     * keeps it correct under paging: the outer {@code limit} slices an already
     * correct running total instead of restarting it from the middle of history.
     */
    public List<StockViews.MovementLine> history(UUID productId, UUID locationId, int limit) {
        return db.sql(
                        """
                        with ordered as (
                            select m.*,
                                   sum(m.qty_delta) over (
                                       partition by m.product_id, m.location_id, m.batch_id
                                       order by m.occurred_at, m.id
                                       rows between unbounded preceding and current row
                                   ) as balance_after
                            from stock_movements m
                            where m.product_id = cast(:productId as uuid)
                              and (cast(:locationId as uuid) is null
                                   or m.location_id = cast(:locationId as uuid))
                        )
                        select o.id, o.occurred_at, o.recorded_at, o.reason, o.qty_delta,
                               o.balance_after, o.actor, o.note, o.ref_type, o.ref_id,
                               o.location_id, l.code as location_code,
                               o.batch_id, b.lot_code, o.unit_cost, o.reversal_of_id,
                               exists (select 1 from stock_movements r
                                        where r.reversal_of_id = o.id) as reversed
                        from ordered o
                            join locations l on l.id = o.location_id
                            left join batches b on b.id = o.batch_id
                        order by o.occurred_at desc, o.id desc
                        limit :limit
                        """)
                .param("productId", productId)
                .param("locationId", locationId)
                .param("limit", limit)
                .query(StockQueryService::mapMovementLine)
                .list();
    }

    /**
     * Products at or below their reorder point.
     *
     * <p>Summed across every location, since a reorder decision is about total
     * holding, not one shelf. Products with a reorder point of zero are excluded
     * — zero means "not tracked for reordering", and including them would report
     * every out-of-stock item forever.
     */
    public List<StockViews.LowStock> lowStock() {
        return db.sql(
                        """
                        select p.id   as product_id,
                               p.sku  as sku,
                               p.name as product_name,
                               p.unit as unit,
                               coalesce(sum(soh.qty), 0) as on_hand,
                               p.reorder_point           as reorder_point
                        from products p
                            left join stock_on_hand soh on soh.product_id = p.id
                        where p.archived_at is null
                          and p.reorder_point > 0
                        group by p.id, p.sku, p.name, p.unit, p.reorder_point
                        having coalesce(sum(soh.qty), 0) <= p.reorder_point
                        order by (coalesce(sum(soh.qty), 0) - p.reorder_point), p.name
                        """)
                .query(
                        (ResultSet rs, int row) -> {
                            Qty onHand = qty(rs, "on_hand");
                            Qty reorderPoint = qty(rs, "reorder_point");
                            return new StockViews.LowStock(
                                    rs.getObject("product_id", UUID.class),
                                    rs.getString("sku"),
                                    rs.getString("product_name"),
                                    ProductUnit.valueOf(rs.getString("unit")),
                                    onHand,
                                    reorderPoint,
                                    reorderPoint.minus(onHand));
                        })
                .list();
    }

    /**
     * Batches still holding stock that expire within {@code withinDays},
     * including those already expired.
     *
     * <p>This is the query that sells the system to a pharmacy: expired stock is
     * money already lost plus a regulatory problem, and the only way to avoid it
     * is to see it coming. Already-expired lots sort first.
     */
    public List<StockViews.NearExpiry> nearExpiry(int withinDays) {
        return db.sql(
                        """
                        select sal.product_id, p.sku, p.name as product_name,
                               sal.location_id, l.code as location_code,
                               sal.batch_id, sal.lot_code, sal.expires_on,
                               (sal.expires_on - current_date) as days_remaining,
                               sal.qty
                        from stock_available_lots sal
                            join products  p on p.id = sal.product_id
                            join locations l on l.id = sal.location_id
                        where sal.expires_on is not null
                          and sal.expires_on <= current_date + cast(:withinDays as integer)
                        order by sal.expires_on, p.name
                        """)
                .param("withinDays", withinDays)
                .query(
                        (ResultSet rs, int row) ->
                                new StockViews.NearExpiry(
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("sku"),
                                        rs.getString("product_name"),
                                        rs.getObject("location_id", UUID.class),
                                        rs.getString("location_code"),
                                        rs.getObject("batch_id", UUID.class),
                                        rs.getString("lot_code"),
                                        rs.getObject("expires_on", LocalDate.class),
                                        rs.getLong("days_remaining"),
                                        qty(rs, "qty")))
                .list();
    }

    /**
     * Lots of a product available at a location, earliest expiry first — the
     * candidate list a FEFO pick chooses from.
     *
     * <p>Undated lots sort last: dated stock is the stock at risk of a write-off,
     * so it should leave first.
     */
    public List<StockViews.OnHand> availableLots(UUID productId, UUID locationId) {
        return db.sql(
                        """
                        select p.id as product_id, p.sku, p.name as product_name, p.unit,
                               l.id as location_id, l.code as location_code,
                               sal.batch_id, sal.lot_code, sal.expires_on, sal.qty,
                               null::timestamptz as last_movement_at
                        from stock_available_lots sal
                            join products  p on p.id = sal.product_id
                            join locations l on l.id = sal.location_id
                        where sal.product_id = cast(:productId as uuid)
                          and sal.location_id = cast(:locationId as uuid)
                        order by sal.expires_on nulls last, sal.lot_code
                        """)
                .param("productId", productId)
                .param("locationId", locationId)
                .query(StockQueryService::mapOnHand)
                .list();
    }

    // ------------------------------------------------------------------
    // Row mapping
    // ------------------------------------------------------------------

    private static StockViews.OnHand mapOnHand(ResultSet rs, int row) throws SQLException {
        return new StockViews.OnHand(
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("product_name"),
                ProductUnit.valueOf(rs.getString("unit")),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getObject("batch_id", UUID.class),
                rs.getString("lot_code"),
                rs.getObject("expires_on", LocalDate.class),
                qty(rs, "qty"),
                rs.getObject("last_movement_at", java.time.Instant.class));
    }

    private static StockViews.MovementLine mapMovementLine(ResultSet rs, int row) throws SQLException {
        return new StockViews.MovementLine(
                rs.getLong("id"),
                rs.getObject("occurred_at", java.time.Instant.class),
                rs.getObject("recorded_at", java.time.Instant.class),
                MovementReason.valueOf(rs.getString("reason")),
                qty(rs, "qty_delta"),
                qty(rs, "balance_after"),
                rs.getString("actor"),
                rs.getString("note"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getObject("batch_id", UUID.class),
                rs.getString("lot_code"),
                rs.getBigDecimal("unit_cost"),
                rs.getObject("reversal_of_id", Long.class),
                rs.getBoolean("reversed"));
    }

    /**
     * Reads a {@code numeric} column as an exact {@link Qty}.
     *
     * <p>Via {@code BigDecimal}, never {@code getDouble} — the whole point of
     * {@link Qty} is that no binary float touches a quantity, and a
     * {@code getDouble} here would undo that at the last step.
     */
    private static Qty qty(ResultSet rs, String column) throws SQLException {
        var value = rs.getBigDecimal(column);
        return value == null ? Qty.ZERO : Qty.of(value);
    }
}
