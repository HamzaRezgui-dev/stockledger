package dev.hamzarezgui.stockledger.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRepository extends JpaRepository<BatchEntity, UUID> {

    Optional<BatchEntity> findByProductIdAndLotCode(UUID productId, String lotCode);

    List<BatchEntity> findByProductIdOrderByExpiresOnAsc(UUID productId);
}
