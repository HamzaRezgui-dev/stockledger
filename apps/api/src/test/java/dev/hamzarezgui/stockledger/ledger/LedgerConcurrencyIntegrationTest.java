package dev.hamzarezgui.stockledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hamzarezgui.stockledger.AbstractIntegrationTest;
import dev.hamzarezgui.stockledger.db.LocationEntity;
import dev.hamzarezgui.stockledger.db.ProductEntity;
import dev.hamzarezgui.stockledger.domain.InsufficientStockException;
import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the central claim of {@code docs/adr/0003-serializable-transactions.md}:
 * concurrent movements cannot drive stock negative.
 *
 * <p>Deliberately not written with mocks or a single thread. The anomaly being
 * prevented only exists between two genuinely concurrent transactions on two
 * separate connections, so anything less would pass while proving nothing.
 */
class LedgerConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LedgerFacade facade;
    @Autowired private LedgerService service;
    @Autowired private DataSource dataSource;

    /**
     * The anomaly itself, forced deterministically at the JDBC level.
     *
     * <p>Both transactions read the balance <em>before</em> either writes — a
     * barrier guarantees that interleaving rather than hoping for it — then both
     * insert a sale of the only unit in stock. Under {@code READ COMMITTED} both
     * would commit and stock would land at -1. Under {@code SERIALIZABLE},
     * Postgres detects the read-write dependency cycle and aborts one with
     * {@code 40001}.
     *
     * <p>This is the test that justifies the isolation level. It bypasses the
     * service on purpose: the claim under test is about Postgres, not about our
     * code.
     */
    @Test
    @DisplayName("SERIALIZABLE aborts one of two transactions racing for the last unit")
    void serializableDetectsTheRaceForTheLastUnit() throws Exception {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        // One unit in stock. Both threads will try to sell it.
        facade.post(PostMovementCommand.of(
                product.getId(), location.getId(), null, qty("1"), MovementReason.RECEIPT, "setup"));

        var bothHaveRead = new CyclicBarrier(2);
        var failures = new CopyOnWriteArrayList<String>();
        var successes = new AtomicInteger();

        Callable<Void> sellTheLastUnit = () -> {
            try (Connection cx = dataSource.getConnection()) {
                cx.setAutoCommit(false);
                cx.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try {
                    // 1. Read the balance — this is the predicate read that SSI tracks.
                    Qty balance = readBalance(cx, product.getId(), location.getId());
                    assertThat(balance).isEqualTo(qty("1"));

                    // 2. Wait for the other transaction to read the same thing.
                    //    Without this, one might commit before the other reads,
                    //    and there would be no conflict to detect.
                    bothHaveRead.await(20, TimeUnit.SECONDS);

                    // 3. Both decide the sale is fine, because both saw 1 in stock.
                    insertSale(cx, product.getId(), location.getId());
                    cx.commit();
                    successes.incrementAndGet();
                } catch (Exception e) {
                    cx.rollback();
                    failures.add(sqlState(e));
                }
            }
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> running =
                    List.of(pool.submit(sellTheLastUnit), pool.submit(sellTheLastUnit));
            for (Future<Void> f : running) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Exactly one wins. The other is told to try again.
        assertThat(successes.get()).as("exactly one transaction commits").isEqualTo(1);
        assertThat(failures)
                .as("the loser fails with serialization_failure (40001), not some other error")
                .containsExactly("40001");

        // And the ledger lands on zero — never -1.
        assertThat(service.balanceOf(product.getId(), location.getId(), null)).isEqualTo(Qty.ZERO);
    }

    /**
     * The same pressure applied through the real write path, proving the retry
     * layer turns detected conflicts into completed work.
     *
     * <p>Twenty threads each sell one unit from a stock of exactly twenty. Every
     * one must succeed — the conflicts are real, so each success means a
     * {@code 40001} was caught and retried against fresh state — and the final
     * balance must be exactly zero.
     *
     * <p>This asserts an invariant rather than a particular interleaving, so it
     * is not flaky: any scheduling that ends with a balance other than zero is a
     * genuine bug.
     */
    @Test
    @DisplayName("20 concurrent sales of 20 units all succeed and land exactly on zero")
    void concurrentSalesRetryAndSettleOnZero() throws Exception {
        int threads = 20;
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        facade.post(PostMovementCommand.of(
                product.getId(),
                location.getId(),
                null,
                qty(String.valueOf(threads)),
                MovementReason.RECEIPT,
                "setup"));

        var startTogether = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Exception>();
        var sold = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        facade.post(PostMovementCommand.of(
                                product.getId(),
                                location.getId(),
                                null,
                                qty("-1"),
                                MovementReason.SALE,
                                "cashier"));
                        sold.incrementAndGet();
                    } catch (Exception e) {
                        errors.add(e);
                    }
                    return null;
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("every sale should succeed, retrying through conflicts").isEmpty();
        assertThat(sold.get()).isEqualTo(threads);
        assertThat(service.balanceOf(product.getId(), location.getId(), null)).isEqualTo(Qty.ZERO);
    }

    /**
     * Oversubscription: more concurrent sales than there is stock.
     *
     * <p>The count must split exactly — the available units sell, the rest are
     * refused — with no thread reading a stale balance and slipping through. This
     * is the case where a lost update would show up as overselling.
     */
    @Test
    @DisplayName("25 concurrent sales of 20 units: exactly 20 succeed, 5 refused, balance zero")
    void oversubscriptionRefusesExactlyTheExcess() throws Exception {
        int threads = 25;
        int available = 20;
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        facade.post(PostMovementCommand.of(
                product.getId(),
                location.getId(),
                null,
                qty(String.valueOf(available)),
                MovementReason.RECEIPT,
                "setup"));

        var startTogether = new CountDownLatch(1);
        var sold = new AtomicInteger();
        var refused = new AtomicInteger();
        var unexpected = new CopyOnWriteArrayList<Exception>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        facade.post(PostMovementCommand.of(
                                product.getId(),
                                location.getId(),
                                null,
                                qty("-1"),
                                MovementReason.SALE,
                                "cashier"));
                        sold.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        // The correct answer once stock runs out, and it must not
                        // be retried — retrying a deterministic refusal would only
                        // delay the same outcome.
                        refused.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.add(e);
                    }
                    return null;
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        assertThat(sold.get()).as("never oversells").isEqualTo(available);
        assertThat(refused.get()).isEqualTo(threads - available);
        assertThat(service.balanceOf(product.getId(), location.getId(), null)).isEqualTo(Qty.ZERO);
    }

    /**
     * Idempotency under concurrency: the same key sent by many threads at once
     * must produce exactly one movement.
     *
     * <p>The pre-flight lookup in {@link LedgerFacade} is not atomic with the
     * insert, so several threads genuinely pass it. The unique index lets one
     * win, and the losers must recognise the duplicate and replay the winner's
     * row rather than erroring — otherwise a client retrying a timed-out request
     * would double-post stock.
     */
    @Test
    @DisplayName("the same idempotency key posted 10x concurrently writes exactly one movement")
    void idempotencyKeyCollapsesConcurrentDuplicates() throws Exception {
        int threads = 10;
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        String key = "receipt-" + UUID.randomUUID();

        var startTogether = new CountDownLatch(1);
        var ids = new CopyOnWriteArrayList<Long>();
        var errors = new CopyOnWriteArrayList<Exception>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        var saved = facade.post(new PostMovementCommand(
                                product.getId(),
                                location.getId(),
                                null,
                                qty("5"),
                                MovementReason.RECEIPT,
                                null,
                                null,
                                null,
                                "warehouse",
                                null,
                                key,
                                null));
                        ids.add(saved.getId());
                    } catch (Exception e) {
                        errors.add(e);
                    }
                    return null;
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("a duplicate key is a replay, not an error").isEmpty();
        assertThat(ids).hasSize(threads);
        assertThat(ids.stream().distinct().toList())
                .as("every caller sees the same single movement")
                .hasSize(1);
        // Stock moved once, not ten times.
        assertThat(service.balanceOf(product.getId(), location.getId(), null)).isEqualTo(qty("5"));
    }

    // ------------------------------------------------------------------
    // Raw JDBC helpers for the deterministic race
    // ------------------------------------------------------------------

    private static Qty readBalance(Connection cx, UUID productId, UUID locationId) throws Exception {
        try (PreparedStatement ps = cx.prepareStatement(
                """
                select coalesce(sum(qty_delta), 0)
                from stock_movements
                where product_id = ? and location_id = ? and batch_id is null
                """)) {
            ps.setObject(1, productId);
            ps.setObject(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Qty.of(rs.getBigDecimal(1));
            }
        }
    }

    private static void insertSale(Connection cx, UUID productId, UUID locationId) throws Exception {
        try (PreparedStatement ps = cx.prepareStatement(
                """
                insert into stock_movements
                    (product_id, location_id, qty_delta, reason, actor)
                values (?, ?, -1, 'SALE', 'racer')
                """)) {
            ps.setObject(1, productId);
            ps.setObject(2, locationId);
            ps.executeUpdate();
        }
    }

    /** The SQLSTATE of a failure, so the assertion names the exact anomaly. */
    private static String sqlState(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "unknown: " + e;
    }
}
