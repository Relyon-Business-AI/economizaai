package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, UUID> {

    interface ProductMarketCoordinates {
        UUID getProductId();
        BigDecimal getLatitude();
        BigDecimal getLongitude();
    }

    long countByProduct(Product product);

    /** Observations with no audit row — leftovers from a deleted account (LGPD keeps the
     * anonymized observation but cascades its audit link away). On dev this gauges test-scan
     * garbage the nightly E2E purge should keep near zero; a weekly job alerts if it climbs. */
    @Query("SELECT COUNT(po) FROM PriceObservation po WHERE NOT EXISTS "
            + "(SELECT 1 FROM PriceObservationAudit a WHERE a.observation = po)")
    long countOrphaned();

    /** Bulk-delete orphaned observations (no audit row). Dev-only cleanup — on prod these
     * are LGPD-preserved anonymized aggregates and must NOT be deleted. Gated at the endpoint. */
    @Modifying
    @Query("DELETE FROM PriceObservation po WHERE NOT EXISTS "
            + "(SELECT 1 FROM PriceObservationAudit a WHERE a.observation = po)")
    int deleteOrphaned();

    @Modifying
    @Query("UPDATE PriceObservation po SET po.product = :survivor WHERE po.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);

    // Physical index reads: the "cheapest market near you" series. Filter to IN_STORE so
    // online observations (national, different price dynamics) never leak into geo results.
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.marketCnpj = :marketCnpj
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentByProductAndMarket(@Param("productId") UUID productId,
                                                       @Param("marketCnpj") String marketCnpj,
                                                       @Param("since") LocalDateTime since);

    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentByProduct(@Param("productId") UUID productId,
                                               @Param("since") LocalDateTime since);

    /** Online index: recent ONLINE observations for a product, across all sellers (national). */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.channel = 'ONLINE'
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentOnlineByProduct(@Param("productId") UUID productId,
                                                     @Param("since") LocalDateTime since);

    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.observedAt >= :since
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
    """)
    List<PriceObservation> findRecent(@Param("since") LocalDateTime since);

    @Query("""
        SELECT DISTINCT po.product.id FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.marketCnpj IN (
              SELECT DISTINCT r.cnpjEmitente FROM Receipt r
              WHERE r.household.id = :householdId
                AND r.status = 'CONFIRMED'
                AND r.cnpjEmitente IS NOT NULL
          )
    """)
    List<UUID> findProductIdsObservedAtVisitedMarkets(@Param("productIds") List<UUID> productIds,
                                                      @Param("householdId") UUID householdId);

    @Query("""
        SELECT DISTINCT po.product.id FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND EXISTS (
              SELECT 1 FROM MarketLocation ml
              WHERE ml.cnpj IN (
                  SELECT DISTINCT r.cnpjEmitente FROM Receipt r
                  WHERE r.household.id = :householdId
                    AND r.status = 'CONFIRMED'
                    AND r.cnpjEmitente IS NOT NULL
              )
              AND (
                  (po.ibgeCityCode IS NOT NULL AND po.ibgeCityCode = ml.ibgeCityCode)
                  OR (
                      po.ibgeCityCode IS NULL
                      AND ml.ibgeCityCode IS NULL
                      AND po.city IS NOT NULL
                      AND ml.city IS NOT NULL
                      AND LOWER(po.city) = LOWER(ml.city)
                      AND po.state = ml.state
                  )
              )
          )
    """)
    List<UUID> findProductIdsObservedInHouseholdCities(@Param("productIds") List<UUID> productIds,
                                                       @Param("householdId") UUID householdId);

    @Query("""
        SELECT DISTINCT po.product.id AS productId, ml.latitude AS latitude, ml.longitude AS longitude
        FROM PriceObservation po
        JOIN MarketLocation ml ON ml.cnpj = po.marketCnpj
        WHERE po.product.id IN :productIds
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND ml.latitude IS NOT NULL
          AND ml.longitude IS NOT NULL
    """)
    List<ProductMarketCoordinates> findProductMarketCoordinates(@Param("productIds") List<UUID> productIds);
}
