package com.relyon.economizai.repository;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt> {

    Optional<Receipt> findByChaveAcesso(String chaveAcesso);

    // Sweeper: PROCESSING rows older than the ingest timeout are stuck (commit-time
    // failure, app restart mid-ingest, or pool rejection) and must be failed.
    List<Receipt> findByStatusAndCreatedAtBefore(ReceiptStatus status, LocalDateTime cutoff);

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

    /** (householdId, receiptCount) for the given households — batch enrichment for the admin user list. */
    @Query("SELECT receipt.household.id, count(receipt) FROM Receipt receipt "
            + "WHERE receipt.household.id IN :householdIds GROUP BY receipt.household.id")
    List<Object[]> countByHouseholdIds(List<UUID> householdIds);

    // --- Ingestion health (ops dashboard) ---

    /** (status, count) for receipts submitted since the window start. */
    @Query("SELECT receipt.status, count(receipt) FROM Receipt receipt "
            + "WHERE receipt.createdAt >= :since GROUP BY receipt.status")
    List<Object[]> statusBreakdownSince(@Param("since") LocalDateTime since);

    /** (uf, status, count) since the window start — per-state pipeline outcome. */
    @Query("SELECT receipt.uf, receipt.status, count(receipt) FROM Receipt receipt "
            + "WHERE receipt.createdAt >= :since GROUP BY receipt.uf, receipt.status")
    List<Object[]> ufStatusBreakdownSince(@Param("since") LocalDateTime since);

    /**
     * (errorKey, count) grouped by the machine key before the ':' in parseErrorReason
     * (native, so the args after ':' don't fragment the buckets). Most frequent first.
     */
    @Query(value = "SELECT split_part(parse_error_reason, ':', 1) AS error_key, count(*) AS hits "
            + "FROM receipts WHERE created_at >= :since AND parse_error_reason IS NOT NULL "
            + "GROUP BY split_part(parse_error_reason, ':', 1) ORDER BY hits DESC",
            nativeQuery = true)
    List<Object[]> errorReasonBreakdownSince(@Param("since") LocalDateTime since);

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
