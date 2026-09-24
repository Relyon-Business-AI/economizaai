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
