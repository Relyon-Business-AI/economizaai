package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt>,
        ReceiptAggregationRepository {

    Optional<Receipt> findByChaveAcesso(String chaveAcesso);

    // AI merchant-review input: the distinct merchants we've seen (bounded sample).
    @Query("SELECT DISTINCT r.cnpjEmitente, r.marketName FROM Receipt r WHERE r.marketName IS NOT NULL")
    List<Object[]> findDistinctMerchants(Pageable pageable);

    // Sweeper: PROCESSING rows older than the ingest timeout are stuck (commit-time
    // failure, app restart mid-ingest, or pool rejection) and must be failed.
    List<Receipt> findByStatusAndCreatedAtBefore(ReceiptStatus status, LocalDateTime cutoff);

    // Paced import worker: oldest IMPORT_QUEUED first, a small page at a time, and a
    // live count of in-flight PROCESSING rows so the worker yields to real scans.
    List<Receipt> findByStatusOrderByCreatedAtAsc(ReceiptStatus status, Pageable pageable);

    long countByStatus(ReceiptStatus status);

    // Import staging: every non-confirmed import receipt for the household, so the
    // import screen rehydrates exactly what the user left (queued/processing/ready/failed).
    // Confirmed rows drop off — they've been saved definitively.
    List<Receipt> findByHouseholdIdAndOriginAndStatusNotOrderByCreatedAtDesc(
            UUID householdId, ReceiptOrigin origin, ReceiptStatus status);

    // Import completion: the worker fires a "done" notification when a user's import
    // batch has no more in-flight notas (queued/processing), reporting how many are
    // ready to review vs failed.
    long countByUserIdAndOriginAndStatusIn(UUID userId, ReceiptOrigin origin, Collection<ReceiptStatus> statuses);

    long countByUserIdAndOriginAndStatus(UUID userId, ReceiptOrigin origin, ReceiptStatus status);

    boolean existsByChaveAcesso(String chaveAcesso);

    boolean existsByHouseholdIdAndChaveAcesso(UUID householdId, String chaveAcesso);

    // Deletion guard: a household must NOT be deleted while any receipt still points
    // at it as current home OR as origin (parked data awaiting restore on split).
    boolean existsByHouseholdId(UUID householdId);

    boolean existsByOriginHouseholdId(UUID householdId);

    List<Receipt> findAllByHouseholdId(UUID householdId);

    /** Seeded dev receipts carry a fixed marker payload — lets a re-seed replace earlier ones. */
    List<Receipt> findAllByHouseholdIdAndQrPayload(UUID householdId, String qrPayload);

    List<Receipt> findAllByOriginHouseholdId(UUID householdId);

    // On leave: the leaver's earliest own receipt whose ORIGIN differs from the
    // household they're currently in — that origin is the "home" to reclaim. Keyed on
    // origin (not current location): after a merge the receipt lives in the target,
    // but its origin still points at the leaver's pre-merge home.
    Optional<Receipt> findFirstByUserIdAndOriginHouseholdIdNotOrderByCreatedAtAsc(UUID userId, UUID originHouseholdId);

    // Consent copy: the grantor's own receipts currently in the shared household, to
    // duplicate into the requester's destination on approval.
    List<Receipt> findAllByUserIdAndHouseholdId(UUID userId, UUID householdId);

    Optional<Receipt> findByHouseholdIdAndChaveAcesso(UUID householdId, String chaveAcesso);

    long countByHouseholdIdAndStatus(UUID householdId, ReceiptStatus status);

    /**
     * How many receipts the user submitted at/after the given instant, ANY
     * status. Counting all statuses (not just CONFIRMED) closes the gaming
     * loop where a FREE user rejects/deletes-and-resubmits to dodge the cap.
     */
    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, LocalDateTime since);

    long countByHouseholdIdAndStatusAndConfirmedAtAfter(UUID householdId, ReceiptStatus status, LocalDateTime since);

    // --- Admin metrics: internal-account filter ---
    // `:includeInternal = false` (dashboard default) drops receipts submitted by
    // admins / test accounts (@economizaai.app, Firebase Test Lab) and anyone flagged
    // excludedFromMetrics — so a bulk admin import doesn't inflate the numbers. Mirror
    // of UserRepository.INTERNAL_FILTER, joined through the receipt's submitting user.
    // Each query joins `receipt.user receiptUser` before applying it.
    String RECEIPT_INTERNAL_FILTER =
            " AND (:includeInternal = TRUE OR (receiptUser.role <> 'ADMIN' "
            + "AND receiptUser.excludedFromMetrics = FALSE "
            + "AND lower(receiptUser.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(receiptUser.email) NOT LIKE '%@cloudtestlabaccounts.com'))";

    // Native-SQL twin of the above (joins `users u ON u.id = receipts.user_id`).
    String NATIVE_RECEIPT_INTERNAL_FILTER =
            " AND (:includeInternal = TRUE OR (u.role <> 'ADMIN' "
            + "AND u.excluded_from_metrics = FALSE "
            + "AND lower(u.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(u.email) NOT LIKE '%@cloudtestlabaccounts.com'))";

    // --- Admin overview (cross-area KPIs) ---

    /** Total receipts, honoring the internal-account filter. */
    @Query("SELECT count(receipt) FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE 1 = 1" + RECEIPT_INTERNAL_FILTER)
    long countReceipts(boolean includeInternal);

    @Query("SELECT count(receipt) FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.createdAt >= :since" + RECEIPT_INTERNAL_FILTER)
    long countByCreatedAtGreaterThanEqual(@Param("since") LocalDateTime since, boolean includeInternal);

    /** Global confirmed spend (all households) — the collaborative total. */
    @Query("SELECT COALESCE(sum(receipt.totalAmount), 0) FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.status = 'CONFIRMED'" + RECEIPT_INTERNAL_FILTER)
    BigDecimal sumConfirmedTotal(boolean includeInternal);

    /** Distinct households that scanned at least one receipt since the cutoff (active users proxy). */
    @Query("SELECT count(distinct receipt.household.id) FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.createdAt >= :since" + RECEIPT_INTERNAL_FILTER)
    long countActiveHouseholdsSince(@Param("since") LocalDateTime since, boolean includeInternal);

    // --- Market intelligence (admin) ---

    /** Most-scanned markets (confirmed): (cnpj, name, scans, spend). One row per CNPJ (per store unit). */
    @Query("SELECT receipt.cnpjEmitente, MIN(receipt.marketName), count(receipt), COALESCE(sum(receipt.totalAmount), 0) "
            + "FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.status = 'CONFIRMED' AND receipt.cnpjEmitente IS NOT NULL" + RECEIPT_INTERNAL_FILTER
            + " GROUP BY receipt.cnpjEmitente ORDER BY count(receipt) DESC")
    List<Object[]> topMarketsByScans(Pageable pageable, boolean includeInternal);

    /**
     * Most-scanned markets grouped by CHAIN — the CNPJ root (first 8 digits = the company
     * registration; branches differ only in the /0002, /0003 suffix). Truly unifies every
     * Zaffari unit into one row regardless of the (inconsistent) market name. Native so
     * regexp_replace can strip any formatting before taking the root. (cnpj, name, scans, spend).
     */
    @Query(value = "SELECT MIN(r.cnpj_emitente) AS cnpj, MIN(r.market_name) AS name, "
            + "count(*) AS scans, COALESCE(sum(r.total_amount), 0)::numeric AS spend "
            + "FROM receipts r JOIN users u ON u.id = r.user_id "
            + "WHERE r.status = 'CONFIRMED' AND r.cnpj_emitente IS NOT NULL" + NATIVE_RECEIPT_INTERNAL_FILTER
            + " GROUP BY substring(regexp_replace(r.cnpj_emitente, '\\D', '', 'g') FROM 1 FOR 8) "
            + "ORDER BY count(*) DESC", nativeQuery = true)
    List<Object[]> topMarketsByChainScans(Pageable pageable, boolean includeInternal);

    /** Confirmed receipts + spend by UF (region): (uf, count, spend). */
    @Query("SELECT receipt.uf, count(receipt), COALESCE(sum(receipt.totalAmount), 0) "
            + "FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.status = 'CONFIRMED'" + RECEIPT_INTERNAL_FILTER
            + " GROUP BY receipt.uf ORDER BY count(receipt) DESC")
    List<Object[]> confirmedReceiptsByUf(boolean includeInternal);

    /** (householdId, receiptCount) for the given households — batch enrichment for the admin user list. */
    @Query("SELECT receipt.household.id, count(receipt) FROM Receipt receipt "
            + "WHERE receipt.household.id IN :householdIds GROUP BY receipt.household.id")
    List<Object[]> countByHouseholdIds(List<UUID> householdIds);

    /** (householdId, sum(confirmed totalAmount)) for the given households — batch spend for the admin user ranking. */
    @Query("SELECT receipt.household.id, COALESCE(sum(receipt.totalAmount), 0) FROM Receipt receipt "
            + "WHERE receipt.status = 'CONFIRMED' AND receipt.household.id IN :householdIds "
            + "GROUP BY receipt.household.id")
    List<Object[]> sumConfirmedTotalByHouseholdIds(List<UUID> householdIds);

    // --- Ingestion health (ops dashboard) ---

    /** (status, count) for receipts submitted since the window start. */
    @Query("SELECT receipt.status, count(receipt) FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.createdAt >= :since" + RECEIPT_INTERNAL_FILTER + " GROUP BY receipt.status")
    List<Object[]> statusBreakdownSince(@Param("since") LocalDateTime since, boolean includeInternal);

    /** (uf, status, count) since the window start — per-state pipeline outcome. */
    @Query("SELECT receipt.uf, receipt.status, count(receipt) FROM Receipt receipt JOIN receipt.user receiptUser "
            + "WHERE receipt.createdAt >= :since" + RECEIPT_INTERNAL_FILTER + " GROUP BY receipt.uf, receipt.status")
    List<Object[]> ufStatusBreakdownSince(@Param("since") LocalDateTime since, boolean includeInternal);

    /**
     * (errorKey, count) grouped by the machine key before the ':' in parseErrorReason
     * (native, so the args after ':' don't fragment the buckets). Most frequent first.
     */
    @Query(value = "SELECT split_part(r.parse_error_reason, ':', 1) AS error_key, count(*) AS hits "
            + "FROM receipts r JOIN users u ON u.id = r.user_id "
            + "WHERE r.created_at >= :since AND r.parse_error_reason IS NOT NULL" + NATIVE_RECEIPT_INTERNAL_FILTER
            + " GROUP BY split_part(r.parse_error_reason, ':', 1) ORDER BY hits DESC",
            nativeQuery = true)
    List<Object[]> errorReasonBreakdownSince(@Param("since") LocalDateTime since, boolean includeInternal);

    // Merchant support gate: scan volume per grey merchant (review queue ranking)
    // and the confirmed receipts to backfill into the index on promotion.
    long countByCnpjEmitente(String cnpjEmitente);

    List<Receipt> findAllByCnpjEmitenteAndStatus(String cnpjEmitente, ReceiptStatus status);

    /** Total R$ of the household's confirmed receipts confirmed at/after the given instant. */
    @Query("""
        SELECT COALESCE(SUM(r.totalAmount), 0) FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.confirmedAt >= :since
    """)
    BigDecimal sumConfirmedTotalSince(@Param("householdId") UUID householdId, @Param("since") LocalDateTime since);

    /**
     * Hour-of-day histogram of the household's confirmed receipts with a non-null
     * issued_at: each row is (hour 0-23, count). Native (the EXTRACT-then-GROUP BY
     * is awkward in JPQL); H2 with {@code MODE=PostgreSQL} supports EXTRACT(HOUR ...),
     * so the @DataJpaTest exercises it. The caller picks the modal hour to infer a
     * household's typical shopping time.
     */
    @Query(value = """
        SELECT CAST(EXTRACT(HOUR FROM r.issued_at) AS INTEGER) AS hourOfDay, COUNT(*) AS receiptCount
        FROM receipts r
        WHERE r.household_id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issued_at IS NOT NULL
        GROUP BY EXTRACT(HOUR FROM r.issued_at)
    """, nativeQuery = true)
    List<HourCount> findConfirmedIssuedHourHistogram(@Param("householdId") UUID householdId);

    /** Projection for {@link #findConfirmedIssuedHourHistogram}: hour-of-day (0-23) and how many receipts fell in it. */
    interface HourCount {
        int getHourOfDay();
        long getReceiptCount();
    }

    /** Distinct CNPJs the household has ever submitted a confirmed receipt from. */
    @Query("""
        SELECT DISTINCT r.cnpjEmitente FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.cnpjEmitente IS NOT NULL
    """)
    List<String> findDistinctCnpjsByHousehold(@Param("householdId") UUID householdId);

    /**
     * Eagerly loads items + each item's product so DTO mapping doesn't N+1
     * when the response includes per-item product fields (e.g. category).
     */
    @Query("""
        SELECT DISTINCT r FROM Receipt r
        LEFT JOIN FETCH r.items i
        LEFT JOIN FETCH i.product
        WHERE r.id = :id
    """)
    Optional<Receipt> findByIdWithItemsAndProducts(@Param("id") UUID id);
}
