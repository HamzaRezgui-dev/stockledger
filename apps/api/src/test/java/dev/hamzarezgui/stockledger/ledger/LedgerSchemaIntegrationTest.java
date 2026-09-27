package dev.hamzarezgui.stockledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.hamzarezgui.stockledger.AbstractIntegrationTest;
import dev.hamzarezgui.stockledger.db.LocationEntity;
import dev.hamzarezgui.stockledger.db.ProductEntity;
import dev.hamzarezgui.stockledger.db.StockMovementEntity;
import dev.hamzarezgui.stockledger.domain.MovementReason;
import dev.hamzarezgui.stockledger.domain.Qty;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The guarantees that belong to the database rather than to Java.
 *
 * <p>Everything here bypasses the service layer on purpose. The claim being
 * tested is that these invariants hold for <em>any</em> code path — a future
 * service, a data-fix script, someone in a {@code psql} session — which is only
 * meaningful if the test attacks the table directly.
 *
 * @see <a href="file:../../../../../../../docs/adr/0001-append-only-ledger.md">ADR 0001</a>
 */
class LedgerSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LedgerFacade facade;
    @Autowired private JdbcClient db;

    private long aMovement() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        return facade.post(PostMovementCommand.of(
                        product.getId(), location.getId(), null, qty("10"), MovementReason.RECEIPT, "setup"))
                .getId();
    }

    @Test
    @DisplayName("UPDATE on the ledger is rejected by trigger")
    void updateIsRejected() {
        long id = aMovement();
        assertThatThrownBySql(() -> db.sql("update stock_movements set qty_delta = 999 where id = ?")
                        .param(id)
                        .update())
                .contains("append-only")
                .contains("UPDATE");
    }

    @Test
    @DisplayName("DELETE on the ledger is rejected by trigger")
    void deleteIsRejected() {
        long id = aMovement();
        assertThatThrownBySql(
                        () -> db.sql("delete from stock_movements where id = ?").param(id).update())
                .contains("append-only")
                .contains("DELETE");
    }

    /**
     * TRUNCATE needs its own trigger: neither the row-level UPDATE nor DELETE
     * trigger fires for it, so without a statement-level guard the entire ledger
     * could be erased while both other protections looked intact.
     */
    @Test
    @DisplayName("TRUNCATE on the ledger is rejected by trigger")
    void truncateIsRejected() {
        aMovement();
        assertThatThrownBySql(() -> db.sql("truncate stock_movements").update())
                .contains("append-only")
                .contains("TRUNCATE");
    }

    /**
     * Excess precision must be refused, not rounded.
     *
     * <p>This is the behaviour a plain {@code numeric(14,3)} column does
     * <em>not</em> give: it silently coerces {@code 1.23456789} to {@code 1.235}
     * before any CHECK runs. The migration therefore uses unconstrained
     * {@code numeric} plus an explicit {@code scale()} check. Without this test
     * the distinction is invisible and a future "tidy-up" would undo it.
     */
    @Test
    @DisplayName("a quantity with more than 3 decimals is rejected, not silently rounded")
    void excessPrecisionIsRejectedNotRounded() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, qty_delta, reason, actor)
                                values (?, ?, 1.23456789, 'RECEIPT', 'attacker')
                                """)
                        .params(product.getId(), location.getId())
                        .update())
                .contains("movements_qty_exact");

        assertThat(db.sql("select count(*) from stock_movements where actor = 'attacker'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a SALE cannot increase stock, whatever the caller says")
    void signMustAgreeWithReason() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, qty_delta, reason, actor)
                                values (?, ?, 5, 'SALE', 'attacker')
                                """)
                        .params(product.getId(), location.getId())
                        .update())
                .contains("movements_sign_check");
    }

    @Test
    @DisplayName("a zero-quantity movement is rejected")
    void zeroQuantityIsRejected() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, qty_delta, reason, actor)
                                values (?, ?, 0, 'RECEIPT', 'attacker')
                                """)
                        .params(product.getId(), location.getId())
                        .update())
                .contains("movements_qty_nonzero");
    }

    /**
     * The database's own non-negative backstop, with the service bypassed
     * entirely — the defence-in-depth claim in ADR 0003.
     */
    @Test
    @DisplayName("the database refuses to go negative even when the service is bypassed")
    void triggerBlocksNegativeStockWithoutTheService() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        facade.post(PostMovementCommand.of(
                product.getId(), location.getId(), null, qty("3"), MovementReason.RECEIPT, "setup"));

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, qty_delta, reason, actor)
                                values (?, ?, -4, 'SALE', 'attacker')
                                """)
                        .params(product.getId(), location.getId())
                        .update())
                .contains("insufficient stock");
    }

    /** A VIRTUAL location that opted in may hold a negative balance. */
    @Test
    @DisplayName("a location with allows_negative may go negative")
    void allowsNegativeLocationMayGoNegative() {
        ProductEntity product = newProduct();
        LocationEntity supplier = newLocation(true);

        StockMovementEntity issued = facade.post(PostMovementCommand.of(
                product.getId(), supplier.getId(), null, qty("-7"), MovementReason.TRANSFER_OUT, "wh"));

        assertThat(issued.getQtyDelta()).isEqualTo(qty("-7"));
    }

    /**
     * Guards against the {@link MovementReason} enum and the SQL CHECK drifting
     * apart.
     *
     * <p>Adding a reason in Java without a migration would fail at runtime the
     * first time anyone used it. Reading the live constraint and comparing makes
     * that a build failure instead.
     */
    @Test
    @DisplayName("MovementReason and the movements_reason_check constraint agree")
    void enumMatchesTheDatabaseConstraint() {
        String definition = db.sql(
                        """
                        select pg_get_constraintdef(oid)
                        from pg_constraint
                        where conname = 'movements_reason_check'
                        """)
                .query(String.class)
                .single();

        List<String> inJava = Arrays.stream(MovementReason.values()).map(Enum::name).sorted().toList();

        for (String reason : inJava) {
            assertThat(definition)
                    .as("reason %s is declared in Java but missing from the database constraint", reason)
                    .contains("'" + reason + "'");
        }

        long countInSql = definition.chars().filter(c -> c == '\'').count() / 2;
        assertThat(countInSql)
                .as("the database constraint allows a reason the Java enum does not define: %s", definition)
                .isEqualTo(inJava.size());
    }

    /** A reversal must mirror its original exactly; the trigger enforces it. */
    @Test
    @DisplayName("a reversal that does not mirror its original is rejected")
    void reversalMustMirrorTheOriginal() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        long original = facade.post(PostMovementCommand.of(
                        product.getId(), location.getId(), null, qty("10"), MovementReason.RECEIPT, "wh"))
                .getId();

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, qty_delta, reason, actor, reversal_of_id)
                                values (?, ?, -3, 'RECEIPT', 'attacker', ?)
                                """)
                        .params(product.getId(), location.getId(), original)
                        .update())
                .contains("must be exactly");
    }

    /**
     * A movement can be reversed at most once, enforced by the unique index on
     * {@code reversal_of_id} — a second reversal would double-count the correction.
     *
     * <p>Reverses a <em>sale</em> rather than a receipt on purpose. Reversing a
     * receipt twice is also blocked, but by the non-negative trigger, which fires
     * first and would mask the constraint actually under test here. Undoing a sale
     * puts stock back, so nothing can go negative and the unique index is the only
     * thing standing in the way.
     */
    @Test
    @DisplayName("a movement cannot be reversed twice")
    void aMovementCannotBeReversedTwice() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        facade.post(PostMovementCommand.of(
                product.getId(), location.getId(), null, qty("20"), MovementReason.RECEIPT, "wh"));
        long sale = facade.post(PostMovementCommand.of(
                        product.getId(), location.getId(), null, qty("-10"), MovementReason.SALE, "cashier"))
                .getId();

        facade.reverse(sale, "supervisor", "customer changed their mind");

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, qty_delta, reason, actor, reversal_of_id)
                                values (?, ?, 10, 'SALE', 'attacker', ?)
                                """)
                        .params(product.getId(), location.getId(), sale)
                        .update())
                .contains("movements_reversal_of_uq");
    }

    /** The service refuses the same double-reversal with a clean domain error. */
    @Test
    @DisplayName("reversing the same movement twice through the service is refused")
    void serviceRefusesDoubleReversal() {
        ProductEntity product = newProduct();
        LocationEntity location = newLocation();
        facade.post(PostMovementCommand.of(
                product.getId(), location.getId(), null, qty("20"), MovementReason.RECEIPT, "wh"));
        long sale = facade.post(PostMovementCommand.of(
                        product.getId(), location.getId(), null, qty("-10"), MovementReason.SALE, "cashier"))
                .getId();

        facade.reverse(sale, "supervisor", "first correction");

        assertThatExceptionOfType(dev.hamzarezgui.stockledger.domain.InvalidMovementException.class)
                .isThrownBy(() -> facade.reverse(sale, "supervisor", "second correction"))
                .withMessageContaining("already been reversed");
    }

    /**
     * The composite foreign key on {@code (batch_id, product_id)} means the
     * database itself guarantees a movement's batch belongs to its product —
     * no application check required.
     */
    @Test
    @DisplayName("a batch from another product is rejected by foreign key")
    void batchMustBelongToTheProduct() {
        ProductEntity batched = newProduct(true, Qty.ZERO);
        ProductEntity otherBatched = newProduct(true, Qty.ZERO);
        LocationEntity location = newLocation();
        var foreignBatch = newBatch(otherBatched, null);

        assertThatThrownBySql(() -> db.sql(
                                """
                                insert into stock_movements
                                    (product_id, location_id, batch_id, qty_delta, reason, actor)
                                values (?, ?, ?, 5, 'RECEIPT', 'attacker')
                                """)
                        .params(batched.getId(), location.getId(), foreignBatch.getId())
                        .update())
                .contains("movements_batch_belongs_to_product");
    }

    /**
     * Asserts that the database rejected a write, and returns an assertion over
     * the <em>whole</em> exception chain flattened into one string.
     *
     * <p>Flattening matters: Spring wraps a {@link SQLException} several layers
     * deep, and the useful text is spread across them — the constraint name often
     * sits in one message and the trigger's own wording in another. Asserting
     * against only the outermost or only the root cause makes a correct rejection
     * look like the wrong one.
     */
    private static org.assertj.core.api.AbstractStringAssert<?> assertThatThrownBySql(Runnable action) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(action::run);
        assertThat(thrown).as("the database should have rejected this write").isNotNull();
        return assertThat(flatten(thrown));
    }

    /** Every message in the cause chain, joined — including SQLException details. */
    private static String flatten(Throwable e) {
        var text = new StringBuilder();
        for (Throwable current = e; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('\n');
            if (current instanceof SQLException sql) {
                for (Throwable next : sql) {
                    text.append(next.getMessage()).append('\n');
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }
}
