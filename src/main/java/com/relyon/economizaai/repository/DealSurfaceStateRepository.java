package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.DealSurfaceState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DealSurfaceStateRepository extends JpaRepository<DealSurfaceState, UUID> {

    Optional<DealSurfaceState> findByUserIdAndProductIdAndMarketCnpj(UUID userId, UUID productId, String marketCnpj);

    /** All surface-state rows for a user — used by the LGPD data export. */
    List<DealSurfaceState> findAllByUserId(UUID userId);

    /**
     * Attribution candidates (Phase D): surface rows for any of the buyer's
     * household users for this (product, market) that are still un-attributed
     * ({@code convertedAt IS NULL}) and were surfaced within the attribution
     * window ({@code lastSurfacedAt >= since}). Newest surfacing first.
     */
    @Query("""
        SELECT state FROM DealSurfaceState state
        WHERE state.userId IN :userIds
          AND state.productId = :productId
          AND state.marketCnpj = :marketCnpj
          AND state.convertedAt IS NULL
          AND state.lastSurfacedAt >= :since
        ORDER BY state.lastSurfacedAt DESC
    """)
    List<DealSurfaceState> findAttributable(@Param("userIds") Collection<UUID> userIds,
                                            @Param("productId") UUID productId,
                                            @Param("marketCnpj") String marketCnpj,
                                            @Param("since") OffsetDateTime since);
}
