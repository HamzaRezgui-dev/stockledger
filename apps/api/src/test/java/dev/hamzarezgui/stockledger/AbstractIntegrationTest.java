package dev.hamzarezgui.stockledger;

import dev.hamzarezgui.stockledger.db.BatchEntity;
import dev.hamzarezgui.stockledger.db.BatchRepository;
import dev.hamzarezgui.stockledger.db.LocationEntity;
import dev.hamzarezgui.stockledger.db.LocationRepository;
import dev.hamzarezgui.stockledger.db.ProductEntity;
import dev.hamzarezgui.stockledger.db.ProductRepository;
import dev.hamzarezgui.stockledger.domain.LocationKind;
import dev.hamzarezgui.stockledger.domain.ProductUnit;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Shared setup for tests that need a real database.
 *
 * <h2>Why there is no cleanup between tests</h2>
 *
 * There cannot be. {@code stock_movements} rejects {@code DELETE} and
 * {@code TRUNCATE} by trigger, and {@code @Transactional} rollback is not an
 * option either: the concurrency tests need real committed transactions on
 * separate connections, which a rolled-back test transaction would prevent.
 *
 * <p>So each test creates its <em>own</em> product and location via
 * {@link #newProduct} / {@link #newLocation}, and asserts only on those. Rows
 * accumulate harmlessly within a run, and the container is thrown away after.
 *
 * <p>This is not a workaround — it is the append-only guarantee being
 * inconvenient in exactly the way it promises to be. A test suite that could
 * wipe the ledger between cases would be evidence the guarantee does not hold.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    /** Makes SKUs and codes unique across a run without coordinating between tests. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired protected ProductRepository products;
    @Autowired protected LocationRepository locations;
    @Autowired protected BatchRepository batches;
    @Autowired protected Clock clock;

    /** A product that does not track batches. */
    protected ProductEntity newProduct() {
        return newProduct(false, Qty.ZERO);
    }

    protected ProductEntity newProduct(boolean tracksBatches, Qty reorderPoint) {
        int n = SEQUENCE.incrementAndGet();
        return products.save(new ProductEntity(
                "SKU-" + n + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Test product " + n,
                null,
                ProductUnit.PIECE,
                tracksBatches,
                reorderPoint,
                clock.instant()));
    }

    /** A warehouse, which must never go negative. */
    protected LocationEntity newLocation() {
        return newLocation(false);
    }

    protected LocationEntity newLocation(boolean allowsNegative) {
        int n = SEQUENCE.incrementAndGet();
        return locations.save(new LocationEntity(
                "LOC-" + n + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Test location " + n,
                allowsNegative ? LocationKind.VIRTUAL : LocationKind.WAREHOUSE,
                allowsNegative,
                clock.instant()));
    }

    protected BatchEntity newBatch(ProductEntity product, LocalDate expiresOn) {
        int n = SEQUENCE.incrementAndGet();
        return batches.save(new BatchEntity(product.getId(), "LOT-" + n, expiresOn, clock.instant()));
    }

    protected static Qty qty(String value) {
        return Qty.parse(value);
    }
}
