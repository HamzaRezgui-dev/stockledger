package dev.hamzarezgui.stockledger.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LocationRepository extends JpaRepository<LocationEntity, UUID> {

    Optional<LocationEntity> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select l from LocationEntity l where l.archivedAt is null order by l.code")
    List<LocationEntity> findAllActive();
}
