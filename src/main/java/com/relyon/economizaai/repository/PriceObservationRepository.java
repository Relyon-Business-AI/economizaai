package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * {@link #findRecentByProduct} for several products in ONE query, limited to the given chains
     * (CNPJ roots). Each chain keeps ALL its observations of the product — the caller prices and
     * k-anon-checks a chain as a whole, exactly like the single-product read.
     */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.marketCnpjRoot IN :cnpjRoots
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
    """)
    List<PriceObservation> findRecentByProductsInChains(@Param("productIds") Collection<UUID> productIds,
                                                        @Param("cnpjRoots") Collection<String> cnpjRoots,
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

    /**
     * Merchant competitor-moves (anonymized): recent IN_STORE observations for the given products
     * in the given states at stores that are NOT part of the merchant's own chains. The caller
     * never sees which store an observation came from — only the aggregated price move per
     * (product, state). {@code excludeRoots} are the merchant's granted CNPJ roots.
     */
    @Query("""
        SELECT po FROM PriceObservation po
        WHERE po.product.id IN :productIds
          AND po.state IN :states
          AND po.marketCnpjRoot NOT IN :excludeRoots
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
    """)
    List<PriceObservation> findRecentByProductIdsAndStatesExcludingChains(
            @Param("productIds") List<UUID> productIds,
            @Param("states") List<String> states,
            @Param("excludeRoots") List<String> excludeRoots,
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

    /** One product's latest disclosable price at a store — see {@link #findLatestDisclosablePricesAtMarket}. */
    interface MarketProductLatestPrice {
        UUID getProductId();
        String getProductName();
        ProductCategory getCategory();
        BigDecimal getLatestPrice();
        LocalDateTime getObservedAt();
    }

    String LATEST_DISCLOSABLE_AT_MARKET = """
        FROM PriceObservation po
        JOIN po.product product
        WHERE po.marketCnpj = :marketCnpj
          AND po.channel = 'IN_STORE'
          AND po.outlier = false
          AND po.observedAt >= :since
          AND LOWER(product.normalizedName) LIKE :namePattern ESCAPE '!'
          AND (:anyCategory = true OR product.category = :category)
          AND NOT EXISTS (
              SELECT newer.id FROM PriceObservation newer
              WHERE newer.product = po.product
                AND newer.marketCnpj = po.marketCnpj
                AND newer.channel = 'IN_STORE'
                AND newer.outlier = false
                AND (newer.observedAt > po.observedAt
                     OR (newer.observedAt = po.observedAt AND newer.createdAt > po.createdAt)
                     OR (newer.observedAt = po.observedAt AND newer.createdAt = po.createdAt
                         AND newer.unitPrice > po.unitPrice)
                     OR (newer.observedAt = po.observedAt AND newer.createdAt = po.createdAt
                         AND newer.unitPrice = po.unitPrice AND newer.id > po.id))
          )
          AND product.id IN (
              SELECT gatedProduct.id
              FROM PriceObservationAudit audit
              JOIN audit.observation contributed
              JOIN contributed.product gatedProduct
              WHERE contributed.marketCnpj = :marketCnpj
                AND contributed.channel = 'IN_STORE'
                AND contributed.outlier = false
                AND contributed.observedAt >= :since
              GROUP BY gatedProduct.id, gatedProduct.categoryId, gatedProduct.category
              HAVING COUNT(DISTINCT audit.householdId) >= :minHouseholds
                 AND COUNT(DISTINCT contributed.id) >= :minObservations
                 AND ((COUNT(DISTINCT audit.householdId) >= :sensitiveMinHouseholds
                       AND COUNT(DISTINCT contributed.id) >= :sensitiveMinObservations)
                      OR ((gatedProduct.categoryId IS NULL OR gatedProduct.categoryId NOT IN :sensitiveCategoryIds)
                          AND (gatedProduct.category IS NULL OR gatedProduct.category <> 'HEALTH')))
          )
    """;

    /**
     * "Tela do mercado": the most recent IN_STORE observation of each product at ONE store inside
     * the window (same tie-break as the best-markets ranking: latest day, then last contributed,
     * then the higher price), one row per product.
     *
     * <p>K-anonymity by tier is applied HERE, per product, in the {@code HAVING} of the audit
     * subquery — a product below its threshold never reaches the service. Every product needs
     * {@code minHouseholds}/{@code minObservations} (the STORE's tier: relaxed for grocery retail,
     * full K otherwise); a sensitive product (category node in {@code sensitiveCategoryIds}, or
     * legacy enum HEALTH) additionally needs the full K ({@code sensitiveMin*}). Observations
     * whose audit row was cascaded away count for nobody (fail-closed).
     *
     * <p>{@code sensitiveCategoryIds} must not be empty and {@code category} must not be null
     * (pass any value with {@code anyCategory = true}) — null/empty binds are not portable.
     */
    @Query(value = """
        SELECT product.id AS productId, product.normalizedName AS productName, product.category AS category,
               po.unitPrice AS latestPrice, po.observedAt AS observedAt
        """ + LATEST_DISCLOSABLE_AT_MARKET,
            countQuery = "SELECT COUNT(po) " + LATEST_DISCLOSABLE_AT_MARKET)
    Page<MarketProductLatestPrice> findLatestDisclosablePricesAtMarket(
            @Param("marketCnpj") String marketCnpj,
            @Param("since") LocalDateTime since,
            @Param("namePattern") String namePattern,
            @Param("anyCategory") boolean anyCategory,
            @Param("category") ProductCategory category,
            @Param("minHouseholds") long minHouseholds,
            @Param("minObservations") long minObservations,
            @Param("sensitiveMinHouseholds") long sensitiveMinHouseholds,
            @Param("sensitiveMinObservations") long sensitiveMinObservations,
            @Param("sensitiveCategoryIds") Collection<UUID> sensitiveCategoryIds,
            Pageable pageable);
}
