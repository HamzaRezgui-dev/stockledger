package dev.hamzarezgui.stockledger.web;

import dev.hamzarezgui.stockledger.db.StockMovementEntity;
import dev.hamzarezgui.stockledger.ledger.LedgerFacade;
import dev.hamzarezgui.stockledger.ledger.PostMovementCommand;
import dev.hamzarezgui.stockledger.query.StockQueryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Writing to and reading back the ledger.
 *
 * <p>There is no {@code PUT} or {@code DELETE} here, and that is the point: the
 * ledger is append-only, so the only way to change what it says is to append a
 * correction. The absence of those verbs is the API telling you so.
 */
@RestController
@RequestMapping("/api/v1/movements")
public class MovementController {

    private final LedgerFacade ledger;
    private final StockQueryService queries;

    public MovementController(LedgerFacade ledger, StockQueryService queries) {
        this.ledger = ledger;
        this.queries = queries;
    }

    /**
     * Append a movement.
     *
     * <p>{@code Idempotency-Key} is a header rather than a body field because it
     * describes the <em>request</em>, not the movement — the same convention
     * Stripe uses. Sending it means a retry after a timeout returns the original
     * movement instead of posting a second one, which matters here because the
     * write path itself retries on serialization conflict (ADR 0003).
     *
     * <p>{@code X-Actor} stands in for the authenticated user until auth exists.
     * It is required, because a ledger entry nobody is accountable for defeats
     * the audit trail that justifies the whole design. Replacing this with a
     * security principal is a one-line change here and nowhere else.
     */
    @PostMapping
    public ResponseEntity<Dtos.MovementResponse> post(
            @Valid @RequestBody Dtos.PostMovementRequest request,
            @RequestHeader(name = "X-Actor") String actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        var command = new PostMovementCommand(
                request.productId(),
                request.locationId(),
                request.batchId(),
                request.parsedQuantity(),
                request.reason(),
                request.refType(),
                request.refId(),
                request.unitCost(),
                actor,
                request.note(),
                idempotencyKey,
                request.occurredAt());

        StockMovementEntity saved = ledger.post(command);
        return ResponseEntity.created(URI.create("/api/v1/movements/" + saved.getId()))
                .body(Dtos.MovementResponse.from(saved));
    }

    /**
     * Correct a movement by appending its mirror image.
     *
     * <p>Modelled as creating a new resource, not modifying one, because that is
     * literally what happens. Responds 201 with the reversal, whose
     * {@code reversalOfId} points back at the original.
     */
    @PostMapping("/{id}/reversal")
    public ResponseEntity<Dtos.MovementResponse> reverse(
            @PathVariable long id,
            @Valid @RequestBody(required = false) Dtos.ReverseMovementRequest request,
            @RequestHeader(name = "X-Actor") String actor) {

        String note = request == null ? null : request.note();
        StockMovementEntity reversal = ledger.reverse(id, actor, note);
        return ResponseEntity.created(URI.create("/api/v1/movements/" + reversal.getId()))
                .body(Dtos.MovementResponse.from(reversal));
    }

    /**
     * The ledger lines behind a product's balance, newest first, each with the
     * balance as it stood immediately after — the "why is this number 11?" view.
     */
    @GetMapping
    public List<Dtos.MovementLineResponse> history(
            @RequestParam UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.history(productId, locationId, Math.clamp(limit, 1, 1000)).stream()
                .map(Dtos.MovementLineResponse::from)
                .toList();
    }
}
