package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceExposure;
import com.relyon.economizaai.model.enums.PriceExposureSurface;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PriceExposureRepository extends JpaRepository<PriceExposure, UUID> {

    /** What one screen already showed one person on one day — the dedup set for that day. */
    List<PriceExposure> findByUserIdAndSurfaceAndShownOn(UUID userId, PriceExposureSurface surface, LocalDate shownOn);

    /**
     * Exposures of a product at a market that can still earn credit for a purchase: shown to
     * someone of the household inside the attribution window and not after the purchase, and
     * not yet attributed. Newest first.
     */
    @Query("""
        SELECT exposure FROM PriceExposure exposure
        WHERE exposure.householdId = :householdId
          AND exposure.productId = :productId
          AND exposure.marketCnpj = :marketCnpj
          AND exposure.convertedAt IS NULL
          AND exposure.shownAt >= :since
          AND exposure.shownAt <= :until
        ORDER BY exposure.shownAt DESC
    """)
    List<PriceExposure> findAttributable(@Param("householdId") UUID householdId,
                                         @Param("productId") UUID productId,
                                         @Param("marketCnpj") String marketCnpj,
                                         @Param("since") OffsetDateTime since,
                                         @Param("until") OffsetDateTime until);
}
