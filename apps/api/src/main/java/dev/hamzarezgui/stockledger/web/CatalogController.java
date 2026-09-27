package dev.hamzarezgui.stockledger.web;

import dev.hamzarezgui.stockledger.db.BatchEntity;
import dev.hamzarezgui.stockledger.db.BatchRepository;
import dev.hamzarezgui.stockledger.db.LocationEntity;
import dev.hamzarezgui.stockledger.db.LocationRepository;
import dev.hamzarezgui.stockledger.db.ProductEntity;
import dev.hamzarezgui.stockledger.db.ProductRepository;
import dev.hamzarezgui.stockledger.domain.InvalidMovementException;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference data: products, locations and batches.
 *
 * <p>Ordinary CRUD at the default isolation level. Only the <em>ledger</em>
 * needs SERIALIZABLE, and applying it here would add contention for no gain —
 * creating a product is not a read-then-write on a derived sum.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final ProductRepository products;
    private final LocationRepository locations;
    private final BatchRepository batches;
    private final Clock clock;

    public CatalogController(
            ProductRepository products,
            LocationRepository locations,
            BatchRepository batches,
            Clock clock) {
        this.products = products;
        this.locations = locations;
        this.batches = batches;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------

    @GetMapping("/products")
    public List<Dtos.ProductResponse> listProducts() {
        return products.findAllActive().stream().map(Dtos.ProductResponse::from).toList();
    }

    /**
     * Look a product up by its barcode — the scan path.
     *
     * <p>Returns 404 rather than an empty list so the scanner UI can distinguish
     * "unknown barcode, offer to create it" from "found it".
     */
    @GetMapping("/products/by-barcode")
    public Dtos.ProductResponse byBarcode(@RequestParam String barcode) {
        return products.findByBarcode(barcode.strip())
                .map(Dtos.ProductResponse::from)
                .orElseThrow(() -> new NotFoundException("no product with barcode " + barcode));
    }

    @GetMapping("/products/{id}")
    public Dtos.ProductResponse getProduct(@PathVariable UUID id) {
        return products.findById(id)
                .map(Dtos.ProductResponse::from)
                .orElseThrow(() -> new NotFoundException("no product with id " + id));
    }

    @PostMapping("/products")
    @Transactional
    public ResponseEntity<Dtos.ProductResponse> createProduct(
            @Valid @RequestBody Dtos.CreateProductRequest request) {

        // Checked explicitly so the client gets a clear 409 naming the conflict,
        // rather than a unique-index violation surfacing as a generic error. The
        // index is still the real guarantee under concurrency.
        if (products.existsBySku(request.sku())) {
            throw new ConflictException("a product with sku " + request.sku() + " already exists");
        }
        String barcode = normalise(request.barcode());
        if (barcode != null && products.existsByBarcode(barcode)) {
            throw new ConflictException("a product with barcode " + barcode + " already exists");
        }

        var saved = products.save(new ProductEntity(
                request.sku().strip(),
                request.name().strip(),
                barcode,
                request.unit(),
                request.tracksBatches(),
                request.parsedReorderPoint(),
                clock.instant()));

        return ResponseEntity.created(URI.create("/api/v1/products/" + saved.getId()))
                .body(Dtos.ProductResponse.from(saved));
    }

    // ------------------------------------------------------------------
    // Locations
    // ------------------------------------------------------------------

    @GetMapping("/locations")
    public List<Dtos.LocationResponse> listLocations() {
        return locations.findAllActive().stream().map(Dtos.LocationResponse::from).toList();
    }

    @PostMapping("/locations")
    @Transactional
    public ResponseEntity<Dtos.LocationResponse> createLocation(
            @Valid @RequestBody Dtos.CreateLocationRequest request) {

        if (locations.existsByCode(request.code())) {
            throw new ConflictException("a location with code " + request.code() + " already exists");
        }

        var saved = locations.save(new LocationEntity(
                request.code().strip(),
                request.name().strip(),
                request.kind(),
                request.allowsNegative(),
                clock.instant()));

        return ResponseEntity.created(URI.create("/api/v1/locations/" + saved.getId()))
                .body(Dtos.LocationResponse.from(saved));
    }

    // ------------------------------------------------------------------
    // Batches
    // ------------------------------------------------------------------

    @GetMapping("/batches")
    public List<Dtos.BatchResponse> listBatches(@RequestParam UUID productId) {
        return batches.findByProductIdOrderByExpiresOnAsc(productId).stream()
                .map(Dtos.BatchResponse::from)
                .toList();
    }

    @PostMapping("/batches")
    @Transactional
    public ResponseEntity<Dtos.BatchResponse> createBatch(
            @Valid @RequestBody Dtos.CreateBatchRequest request) {

        ProductEntity product = products.findById(request.productId())
                .orElseThrow(() -> new NotFoundException("no product with id " + request.productId()));

        // Creating a batch for a product that does not track batches would make a
        // row no movement could ever reference, since the schema's batch trigger
        // rejects a batch_id on such products.
        if (!product.isTracksBatches()) {
            throw new InvalidMovementException(
                    "product %s does not track batches, so it cannot have lots"
                            .formatted(product.getSku()));
        }

        String lotCode = request.lotCode().strip();
        if (batches.findByProductIdAndLotCode(product.getId(), lotCode).isPresent()) {
            throw new ConflictException(
                    "lot %s already exists for product %s".formatted(lotCode, product.getSku()));
        }

        var saved = batches.save(
                new BatchEntity(product.getId(), lotCode, request.expiresOn(), clock.instant()));

        return ResponseEntity.created(URI.create("/api/v1/batches/" + saved.getId()))
                .body(Dtos.BatchResponse.from(saved));
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        // An empty barcode must become NULL, not "". The unique index permits
        // many NULLs but only one empty string, so a second unbarcoded product
        // would be rejected for no good reason.
        return stripped.isEmpty() ? null : stripped;
    }
}
