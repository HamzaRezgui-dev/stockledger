package dev.hamzarezgui.stockledger.domain;

/**
 * A movement was malformed on its own terms — the sign disagreed with the
 * reason, a batch was required and missing, or a referenced entity does not
 * exist.
 *
 * <p>Distinct from {@link InsufficientStockException}, which is a well-formed
 * request that the current stock level refuses. This one would never succeed at
 * any stock level, which is why it maps to 400 rather than 409.
 */
public class InvalidMovementException extends LedgerException {

    public InvalidMovementException(String message) {
        super(message);
    }
}
