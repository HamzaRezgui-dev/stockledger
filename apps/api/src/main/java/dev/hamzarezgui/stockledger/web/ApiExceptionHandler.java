package dev.hamzarezgui.stockledger.web;

import dev.hamzarezgui.stockledger.domain.InsufficientStockException;
import dev.hamzarezgui.stockledger.domain.InvalidMovementException;
import dev.hamzarezgui.stockledger.domain.QtyException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates failures into HTTP.
 *
 * <p>The status codes are chosen to tell a client whether retrying could ever
 * help, because that is the only thing a client can act on automatically:
 *
 * <ul>
 *   <li><b>400</b> — malformed. Retrying the same request is pointless.
 *   <li><b>409</b> — well-formed but refused by current state. Retrying is
 *       pointless <em>now</em> but may succeed after the state changes, which is
 *       exactly the semantics of "insufficient stock".
 *   <li><b>503</b> — transient. Retrying is the correct response, and the
 *       {@code Retry-After} header says so explicitly.
 * </ul>
 *
 * <p>Every body carries a stable {@code code} symbol so clients branch on that
 * rather than parsing prose, which would break the moment a message is reworded.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Not enough stock. 409, not 400: the request was perfectly valid, the
     * warehouse simply cannot satisfy it right now.
     *
     * <p>The body carries the numbers so a UI can say "you have 3, you asked for
     * 5" and offer to post the available quantity, without parsing the message.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Dtos.ErrorResponse> insufficientStock(InsufficientStockException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productId", e.productId());
        details.put("locationId", e.locationId());
        details.put("batchId", e.batchId());
        details.put("available", e.available().toPlainString());
        details.put("requested", e.requested().abs().toPlainString());
        details.put("shortfall", e.shortfall().toPlainString());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dtos.ErrorResponse("INSUFFICIENT_STOCK", e.getMessage(), details));
    }

    /** Malformed movement: sign disagrees with reason, batch missing, unknown reference. */
    @ExceptionHandler(InvalidMovementException.class)
    public ResponseEntity<Dtos.ErrorResponse> invalidMovement(InvalidMovementException e) {
        return ResponseEntity.badRequest()
                .body(Dtos.ErrorResponse.of("INVALID_MOVEMENT", e.getMessage()));
    }

    /**
     * A quantity that cannot be represented exactly — too many decimals, or out
     * of range. 400, because rounding it would be data loss (ADR 0002).
     */
    @ExceptionHandler(QtyException.class)
    public ResponseEntity<Dtos.ErrorResponse> badQuantity(QtyException e) {
        return ResponseEntity.badRequest().body(Dtos.ErrorResponse.of("INVALID_QUANTITY", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Dtos.ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Dtos.ErrorResponse.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Dtos.ErrorResponse> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Dtos.ErrorResponse.of("CONFLICT", e.getMessage()));
    }

    /** Bean-validation failures, flattened to field → message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult()
                .getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        e.getBindingResult()
                .getGlobalErrors()
                .forEach(ge -> fields.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));

        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorResponse("VALIDATION_FAILED", "request validation failed", fields));
    }

    /** A missing {@code X-Actor} is a client bug, so say which header is missing. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Dtos.ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(Dtos.ErrorResponse.of(
                        "MISSING_HEADER", "required header " + e.getHeaderName() + " is missing"));
    }

    /**
     * A serialization conflict that survived every retry.
     *
     * <p>503 with {@code Retry-After}, because this is the one failure where
     * retrying is genuinely the right thing to do — the request was valid and may
     * well succeed unchanged. Reaching here means contention exceeded
     * {@code stockledger.ledger.max-retries}, so it is logged at warn: it is a
     * capacity signal, not a client mistake.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Dtos.ErrorResponse> exhaustedRetries(ConcurrencyFailureException e) {
        log.warn("serialization conflict survived all retries: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Dtos.ErrorResponse.of(
                        "CONCURRENCY_CONFLICT",
                        "the ledger is under heavy contention for this item; please retry"));
    }

    /**
     * A constraint the service did not anticipate — including the schema's own
     * triggers firing.
     *
     * <p>Reaching here for a stock-level problem would mean the service's check
     * was bypassed and the database's backstop caught it instead (ADR 0003), so
     * it is logged at warn with the cause: it points at a real gap in the service
     * layer, not at the caller.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Dtos.ErrorResponse> integrity(DataIntegrityViolationException e) {
        log.warn("database rejected a write the service did not catch first", e);
        String message = e.getMostSpecificCause().getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Dtos.ErrorResponse.of(
                        "CONSTRAINT_VIOLATION",
                        message == null ? "the database rejected this write" : message.strip()));
    }
}
