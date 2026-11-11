package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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

    /** Reassigns a specific set of observations to a target product — the per-signature leg of a split. */
    @Modifying
    @Query("UPDATE PriceObservation po SET po.product = :target WHERE po.id IN :observationIds")
    int repointObservationsTo(@Param("observationIds") Collection<UUID> observationIds,
                              @Param("target") Product target);

    /**
     * Every observation of a product paired with the source receipt it was contributed from
     * (via the internal audit join). Row = [observationId, receiptId, unitPrice, quantity].
     * The variant-split uses (receiptId, unitPrice, quantity) to trace an observation back to
     * the receipt_item whose signature decides which split product it belongs to — observations
     * carry no direct item FK. {@code receiptId} is null when the audit row was cascaded away
     * (deleted account); those stay on the original product.
     */
    @Query("""
        SELECT po.id, audit.receiptId, po.unitPrice, po.quantity
        FROM PriceObservation po
        LEFT JOIN PriceObservationAudit audit ON audit.observation = po
        WHERE po.product.id = :productId
    """)
    List<Object[]> findObservationTraceByProduct(@Param("productId") UUID productId);

    /**
     * Fills city/state on a market's observations that were written before the
     * market was geocoded (city/state are snapshotted at write time and never
     * back-filled otherwise). ONLY touches rows where city is still NULL — an
     * existing snapshot is never overwritten. Called right after a market
     * geocodes. Returns rows updated.
     */
    @Modifying
    @Query("""
        UPDATE PriceObservation po
        SET po.city = :city, po.state = :state
        WHERE po.marketCnpj = :marketCnpj AND po.city IS NULL
    """)
    int backfillLocationForMarket(@Param("marketCnpj") String marketCnpj,
                                  @Param("city") String city,
                                  @Param("state") String state);

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

    interface ProductLowestPrice {
        UUID getProductId();
        BigDecimal getLowestPrice();
    }

    /**
     * Lowest recent in-store unit price per product, over EXACTLY the rows
     * {@link #findRecentByProduct} returns (in-store, non-outlier, since). Any chain median
     * is >= this minimum, so a product whose minimum doesn't beat a threshold can't yield a
     * qualifying market — callers use it to skip the per-product ranking in one query.
     */
    @Query("""
        SELECT po.product.id AS productId, MIN(po.unitPrice) AS lowestPrice
        FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
        GROUP BY po.product.id
    """)
    List<ProductLowestPrice> findLowestRecentInStorePrices(@Param("productIds") Collection<UUID> productIds,
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

    /** Merchant panel (chain side): recent IN_STORE observations across every store of a chain. */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.marketCnpjRoot = :cnpjRoot
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
    """)
    List<PriceObservation> findRecentByMarketCnpjRoot(@Param("cnpjRoot") String cnpjRoot,
                                                      @Param("since") LocalDateTime since);

    /** Promo verification: does any real scanned receipt at the chain, inside the promo
     *  window, confirm the announced price (observed unit price <= announced)? */
    @Query("""
        SELECT COUNT(po) > 0 FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.marketCnpjRoot = :cnpjRoot
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :from
          AND po.observedAt < :to
          AND po.unitPrice <= :maxPrice
    """)
    boolean existsConfirmingObservation(@Param("productId") UUID productId,
                                        @Param("cnpjRoot") String cnpjRoot,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("maxPrice") BigDecimal maxPrice);

    /** In-store typical price (regional): recent IN_STORE observations of one product in the given states. */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id = :productId
          AND po.state IN :states
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentByProductAndStates(@Param("productId") UUID productId,
                                                        @Param("states") Collection<String> states,
                                                        @Param("since") LocalDateTime since);

    /** Merchant panel (region side): recent observations for the given products in the given states. */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.state IN :states
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
    """)
    List<PriceObservation> findRecentByProductIdsAndStates(@Param("productIds") List<UUID> productIds,
                                                           @Param("states") List<String> states,
                                                           @Param("since") LocalDateTime since);

    /** Generic comparison: recent IN_STORE observations across a SET of products (all states). */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
        ORDER BY po.observedAt DESC
    """)
    List<PriceObservation> findRecentByProductIds(@Param("productIds") List<UUID> productIds,
                                                  @Param("since") LocalDateTime since);

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
