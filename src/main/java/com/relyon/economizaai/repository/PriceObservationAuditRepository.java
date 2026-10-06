package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PriceObservationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PriceObservationAuditRepository extends JpaRepository<PriceObservationAudit, UUID> {

    /** Community-scale gauge for onboarding: how many distinct households have ever
     * contributed a (non-outlier) observation. Drives the collaborative cold-start
     * lock — a coarse global signal, not a per-(product,market) k-anon guarantee. */
    @Query("SELECT COUNT(DISTINCT a.householdId) FROM PriceObservationAudit a WHERE a.observation.outlier = false")
    long countDistinctContributingHouseholds();

    /** K-anonymity helper (physical index): how many distinct households contributed IN_STORE
     * observations for a given (product, market) since the cutoff? */
    @Query("""
        SELECT COUNT(DISTINCT a.householdId)
        FROM PriceObservationAudit a
        WHERE a.observation.product.id = :productId
          AND a.observation.marketCnpj = :marketCnpj
          AND a.observation.channel = 'IN_STORE'
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
    """)
    long countDistinctHouseholdsForProductMarket(@Param("productId") UUID productId,
                                                 @Param("marketCnpj") String marketCnpj,
                                                 @Param("since") LocalDateTime since);

    /** K-anonymity helper (online index): distinct households that contributed ONLINE
     * observations for a product nationally (across all sellers) since the cutoff. */
    @Query("""
        SELECT COUNT(DISTINCT a.householdId)
        FROM PriceObservationAudit a
        WHERE a.observation.product.id = :productId
          AND a.observation.channel = 'ONLINE'
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
    """)
    long countDistinctOnlineHouseholdsForProduct(@Param("productId") UUID productId,
                                                 @Param("since") LocalDateTime since);

    /** All audit rows a given receipt contributed — used by the admin purge that
     * removes the anonymized observations a test/erroneous receipt produced (a plain
     * receipt delete cascades these audit rows but NOT the observations they link). */
    List<PriceObservationAudit> findByReceiptId(UUID receiptId);

    /** Batched k-anonymity helper: distinct contributing households per market for a
     * product, in one query (avoids an N+1 over markets in {@code bestMarkets}). */
    @Query("""
        SELECT a.observation.marketCnpj AS cnpj, COUNT(DISTINCT a.householdId) AS households
        FROM PriceObservationAudit a
        WHERE a.observation.product.id = :productId
          AND a.observation.channel = 'IN_STORE'
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
        GROUP BY a.observation.marketCnpj
    """)
    List<MarketHouseholdCount> countDistinctHouseholdsForProductByMarket(@Param("productId") UUID productId,
                                                                         @Param("since") LocalDateTime since);

    /** Projection for {@link #countDistinctHouseholdsForProductByMarket}. */
    interface MarketHouseholdCount {
        String getCnpj();
        long getHouseholds();
    }

    /** Batched k-anonymity helper for community-promo detection: distinct contributing
     *  households per (product, market) in ONE aggregated query — the per-group count
     *  used to be an N+1 over every (product, market) pair on each dashboard build.
     *  {@code HAVING >= :minHouseholds} applies the K-gate in the database, so groups
     *  that fail k-anonymity never even reach the service. */
    @Query("""
        SELECT a.observation.product.id AS productId, a.observation.marketCnpj AS cnpj,
               COUNT(DISTINCT a.householdId) AS households
        FROM PriceObservationAudit a
        WHERE a.observation.channel = 'IN_STORE'
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
        GROUP BY a.observation.product.id, a.observation.marketCnpj
        HAVING COUNT(DISTINCT a.householdId) >= :minHouseholds
    """)
    List<ProductMarketHouseholdCount> countDistinctHouseholdsPerProductMarket(@Param("since") LocalDateTime since,
                                                                              @Param("minHouseholds") long minHouseholds);

    /** Projection for {@link #countDistinctHouseholdsPerProductMarket}. */
    interface ProductMarketHouseholdCount {
        UUID getProductId();
        String getCnpj();
        long getHouseholds();
    }

    /** Merchant-panel k-anon (chain side): distinct households per (product, state)
     *  across every store of a chain. Same K-gate as the public index — the merchant
     *  is a B2B consumer of aggregates, so sub-K rows never reach it. */
    @Query("""
        SELECT a.observation.product.id AS productId, a.observation.state AS state,
               COUNT(DISTINCT a.householdId) AS households
        FROM PriceObservationAudit a
        WHERE a.observation.marketCnpjRoot = :cnpjRoot
          AND a.observation.channel = 'IN_STORE'
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
          AND a.observation.state IS NOT NULL
        GROUP BY a.observation.product.id, a.observation.state
    """)
    List<ProductStateHouseholdCount> countDistinctHouseholdsPerProductStateForChain(
            @Param("cnpjRoot") String cnpjRoot, @Param("since") LocalDateTime since);

    /** Merchant-panel k-anon (region side): distinct households per (product, state)
     *  across the whole index, for the products/states the chain was observed in. */
    @Query("""
        SELECT a.observation.product.id AS productId, a.observation.state AS state,
               COUNT(DISTINCT a.householdId) AS households
        FROM PriceObservationAudit a
        WHERE a.observation.product.id IN :productIds
          AND a.observation.state IN :states
          AND a.observation.channel = 'IN_STORE'
          AND a.observation.outlier = false
          AND a.observation.observedAt >= :since
        GROUP BY a.observation.product.id, a.observation.state
    """)
    List<ProductStateHouseholdCount> countDistinctHouseholdsPerProductState(
            @Param("productIds") List<UUID> productIds,
            @Param("states") List<String> states,
            @Param("since") LocalDateTime since);

    /** Projection for the merchant-panel (product, state) household counts. */
    interface ProductStateHouseholdCount {
        UUID getProductId();
        String getState();
        long getHouseholds();
    }

    /**
     * True when another household has already contributed observations for
     * a receipt sharing this fiscal chave. Used to keep the same NF from
     * counting twice in the collaborative panel when two households both
     * import it (e.g. couple split a bill).
     */
    @Query("""
        SELECT COUNT(a) > 0
        FROM PriceObservationAudit a, Receipt r
        WHERE a.receiptId = r.id
          AND r.chaveAcesso = :chaveAcesso
          AND a.householdId <> :currentHouseholdId
    """)
    boolean existsContributionForChaveFromOtherHousehold(@Param("chaveAcesso") String chaveAcesso,
                                                         @Param("currentHouseholdId") UUID currentHouseholdId);

    /** Promotion backfill guard: has this receipt already contributed observations? */
    boolean existsByReceiptId(UUID receiptId);
}
