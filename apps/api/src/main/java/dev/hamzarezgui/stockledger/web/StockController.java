package dev.hamzarezgui.stockledger.web;

import dev.hamzarezgui.stockledger.query.StockQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Derived stock views. Nothing here is writable — post a movement instead. */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockQueryService queries;

    public StockController(StockQueryService queries) {
        this.queries = queries;
    }

    /** Current stock, optionally narrowed to a product or a location. */
    @GetMapping
    public List<Dtos.OnHandResponse> onHand(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID locationId) {
        return queries.onHand(productId, locationId).stream().map(Dtos.OnHandResponse::from).toList();
    }

    /** Products at or below their reorder point, most urgent first. */
    @GetMapping("/low")
    public List<Dtos.LowStockResponse> lowStock() {
        return queries.lowStock().stream().map(Dtos.LowStockResponse::from).toList();
    }

    /**
     * Batches expiring within {@code withinDays}, including already-expired ones.
     *
     * <p>Default 90 days: long enough to act on for slow-moving stock, short
     * enough that the list stays actionable rather than becoming wallpaper.
     */
    @GetMapping("/expiring")
    public List<Dtos.NearExpiryResponse> nearExpiry(
            @RequestParam(defaultValue = "90") int withinDays) {
        return queries.nearExpiry(Math.clamp(withinDays, 0, 3650)).stream()
                .map(Dtos.NearExpiryResponse::from)
                .toList();
    }

    /**
     * Lots available for a product at a location, earliest expiry first.
     *
     * <p>The candidate list for a FEFO pick: the client shows them in this order
     * so the operator takes the lot that would otherwise be written off first.
     */
    @GetMapping("/lots")
    public List<Dtos.OnHandResponse> availableLots(
            @RequestParam UUID productId, @RequestParam UUID locationId) {
        return queries.availableLots(productId, locationId).stream()
                .map(Dtos.OnHandResponse::from)
                .toList();
    }
}
