package dev.hamzarezgui.stockledger.web;

/**
 * The request is well-formed but conflicts with existing state — a duplicate SKU,
 * a lot code already in use. Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
