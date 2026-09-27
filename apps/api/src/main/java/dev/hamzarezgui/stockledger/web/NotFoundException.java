package dev.hamzarezgui.stockledger.web;

/** A requested resource does not exist. Mapped to 404 by {@link ApiExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
