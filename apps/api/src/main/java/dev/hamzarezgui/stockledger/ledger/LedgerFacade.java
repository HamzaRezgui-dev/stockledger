package dev.hamzarezgui.stockledger.ledger;

import dev.hamzarezgui.stockledger.db.StockMovementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * The entry point for ledger writes: retries transient serialization conflicts
 * and turns a duplicate idempotency key into a replay rather than an error.
 *
 * <h2>Why this is a separate class from {@link LedgerService}</h2>
 *
 * Not organisational taste — it is the only arrangement that works.
 *
 * <ul>
 *   <li>A transaction aborted with {@code 40001} is unusable; every subsequent
 *       statement on it fails. Retrying therefore requires a <em>new</em>
 *       transaction, so the retry boundary must enclose the transaction
 *       boundary, never sit inside it.
 *   <li>Spring implements {@code @Retryable} and {@code @Transactional} with
 *       proxies. A call to {@code this.post(...)} inside a single bean never
 *       leaves the object, so it passes through no proxy and gets neither
 *       behaviour. Two beans means the call crosses a proxy, which is what makes
 *       the annotations apply at all.
 * </ul>
 *
 * <p>Deliberately <strong>not</strong> annotated {@code @Transactional}: adding
 * it here would open a transaction around the retry loop and defeat the whole
 * mechanism.
 *
 * @see <a href="file:../../../../../../../docs/adr/0003-serializable-transactions.md">ADR 0003</a>
 */
@Service
public class LedgerFacade {

    private static final Logger log = LoggerFactory.getLogger(LedgerFacade.class);

    private final LedgerService ledger;

    public LedgerFacade(LedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Append one movement, retrying if it loses a serialization conflict.
     *
     * <p>Retries only {@link ConcurrencyFailureException} — Spring's translation
     * of Postgres's {@code 40001}, which Hibernate wraps, and which
     * {@code @Retryable} still matches because it inspects nested causes. Every
     * other failure is deterministic: an {@code InsufficientStockException} means
     * the stock genuinely is not there, and retrying only delays the same answer.
     *
     * <p>{@code jitter} matters more than it looks. Two transactions that
     * conflict and then back off by exactly the same interval collide again on
     * the next attempt; randomising breaks the lockstep.
     */
    @Retryable(
            includes = ConcurrencyFailureException.class,
            maxRetriesString = "${stockledger.ledger.max-retries}",
            delayString = "${stockledger.ledger.retry-delay-ms}",
            jitterString = "${stockledger.ledger.retry-delay-ms}",
            multiplier = 2.0,
            maxDelay = 1000)
    public StockMovementEntity post(PostMovementCommand cmd) {
        // A key already used means this is a retry of a request whose response
        // the client never saw. Return the original instead of posting twice.
        if (cmd.hasIdempotencyKey()) {
            var existing = ledger.findByIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                log.debug(
                        "idempotency key {} already posted as movement {}; replaying",
                        cmd.idempotencyKey(),
                        existing.get().getId());
                return existing.get();
            }
        }

        try {
            return ledger.post(cmd);
        } catch (DataIntegrityViolationException e) {
            // The check above is not atomic with the insert, so two concurrent
            // requests carrying the same key can both pass it. The unique index
            // lets exactly one win; the loser lands here and reads the winner's
            // row on a fresh transaction. Racing to the same answer is the point
            // of idempotency, so this is a success, not an error.
            if (cmd.hasIdempotencyKey()) {
                var existing = ledger.findByIdempotencyKey(cmd.idempotencyKey());
                if (existing.isPresent()) {
                    log.debug(
                            "idempotency key {} lost an insert race; replaying movement {}",
                            cmd.idempotencyKey(),
                            existing.get().getId());
                    return existing.get();
                }
            }
            throw e;
        }
    }

    /**
     * Reverse a movement, retrying on serialization conflict.
     *
     * <p>Not keyed by an idempotency token, but idempotent in effect: the unique
     * index on {@code reversal_of_id} means a movement can be reversed at most
     * once, so a duplicated request fails loudly rather than double-counting.
     */
    @Retryable(
            includes = ConcurrencyFailureException.class,
            maxRetriesString = "${stockledger.ledger.max-retries}",
            delayString = "${stockledger.ledger.retry-delay-ms}",
            jitterString = "${stockledger.ledger.retry-delay-ms}",
            multiplier = 2.0,
            maxDelay = 1000)
    public StockMovementEntity reverse(Long movementId, String actor, String note) {
        return ledger.reverse(movementId, actor, note);
    }
}
