package dev.hamzarezgui.stockledger.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {

    Optional<ProductEntity> findBySku(String sku);

    /** The barcode-scan path: one scan, one product. */
    Optional<ProductEntity> findByBarcode(String barcode);

    boolean existsBySku(String sku);

    boolean existsByBarcode(String barcode);

    /** Active products only — archived ones stay for history but out of pickers. */
    @Query("select p from ProductEntity p where p.archivedAt is null order by p.name")
    List<ProductEntity> findAllActive();
}
