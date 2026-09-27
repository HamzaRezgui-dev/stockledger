package dev.hamzarezgui.stockledger.domain;

/**
 * Base type for a ledger operation refused on business grounds.
 *
 * <p>The distinction that matters: a {@code LedgerException} is a
 * <em>deterministic answer</em>. Retrying it produces the same refusal, so the
 * retry layer must never retry one — see
 * {@code docs/adr/0003-serializable-transactions.md}. That is the opposite of a
 * serialization conflict, which is transient and should always be retried.
 */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }
}
