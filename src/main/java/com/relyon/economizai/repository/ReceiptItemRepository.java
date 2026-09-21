package com.relyon.economizai.repository;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ReceiptItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptItemRepository extends JpaRepository<ReceiptItem, UUID> {

    /** Latest real purchase of a product — price-math sanity input for LLM pack-size enrichment. */
    Optional<ReceiptItem> findFirstByProductIdOrderByCreatedAtDesc(UUID productId);

    // --- Market intelligence (admin) ---

    /** Most-scanned products across all households (confirmed, non-excluded items). */
    @Query("SELECT item.product.id, COALESCE(item.product.genericName, item.product.normalizedName), count(item) "
            + "FROM ReceiptItem item WHERE item.receipt.status = 'CONFIRMED' AND item.excluded = false "
            + "AND item.product IS NOT NULL "
            + "GROUP BY item.product.id, item.product.genericName, item.product.normalizedName "
            + "ORDER BY count(item) DESC")
    List<Object[]> topProductsByScans(Pageable pageable);

    /** Global spend by category (confirmed, non-excluded), using the confirmation-time snapshot. */
    @Query("SELECT item.categoryAtConfirmation, COALESCE(sum(item.totalPrice), 0), count(item) "
            + "FROM ReceiptItem item WHERE item.receipt.status = 'CONFIRMED' AND item.excluded = false "
            + "GROUP BY item.categoryAtConfirmation")
    List<Object[]> categorySpendGlobal();

    /**
     * The household's own friendly name for each of the given products, taken from its
     * confirmed, non-excluded receipt items — i.e. what the household last called the
     * product on a note (user-typed override or inherited alias). Rows are returned
     * newest-first (by receipt issue date), so callers keep the first hit per product.
     * Used as a shopping-list display fallback when the household hasn't set an explicit
     * {@code household_product_aliases} rename — keeps the list from showing the raw
     * SEFAZ product name ("BATATA PALHA D.NONNA 440G").
     */
    @Query("""
        SELECT ri.product.id, ri.friendlyDescription
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE ri.product.id IN :productIds
          AND r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.friendlyDescription IS NOT NULL
          AND ri.friendlyDescription <> ''
        ORDER BY r.issuedAt DESC NULLS LAST
    """)
    List<Object[]> findLatestFriendlyDescriptionsForHousehold(@Param("productIds") List<UUID> productIds,
                                                              @Param("householdId") UUID householdId);


    @Query("""
        SELECT ri FROM ReceiptItem ri
        JOIN FETCH ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.product IS NULL
          AND ri.excluded = false
        ORDER BY r.issuedAt DESC NULLS LAST, ri.lineNumber ASC
    """)
    List<ReceiptItem> findUnmatchedForHousehold(@Param("householdId") UUID householdId);

    @Modifying
    @Query("""
        UPDATE ReceiptItem ri SET ri.product = :product
        WHERE ri.product IS NULL AND LOWER(ri.ean) = LOWER(:ean) AND ri.excluded = false
    """)
    int linkByEan(@Param("product") Product product, @Param("ean") String ean);

    @Modifying
    @Query("UPDATE ReceiptItem ri SET ri.product = :survivor WHERE ri.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);

    long countByProduct(Product product);

    // ── Matching diagnostics (admin) ──
    // How many confirmed, non-excluded items are linked to a product vs orphaned.
    // UNMATCHED rate = unmatched / (matched + unmatched) — the core matching KPI.
    @Query("""
        SELECT COUNT(ri) FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.status = 'CONFIRMED' AND ri.excluded = false AND ri.product IS NOT NULL
    """)
    long countMatched();

    @Query("""
        SELECT COUNT(ri) FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.status = 'CONFIRMED' AND ri.excluded = false AND ri.product IS NULL
    """)
    long countUnmatched();

    // The most frequent orphan descriptions (normalized), so curators know which
    // dictionary/alias entries would recover the most items. Ordered by frequency.
    @Query("""
        SELECT LOWER(TRIM(ri.rawDescription)) AS description, COUNT(ri) AS occurrences
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.status = 'CONFIRMED' AND ri.excluded = false AND ri.product IS NULL
        GROUP BY LOWER(TRIM(ri.rawDescription))
        ORDER BY COUNT(ri) DESC
    """)
    List<Object[]> topUnmatchedDescriptions(org.springframework.data.domain.Pageable pageable);

    List<ReceiptItem> findAllByProductIdOrderByReceiptIssuedAtAsc(UUID productId);

    /** Same intent as the method above but fetches receipt + household up front,
     *  used by promo detection where we filter by household + receipt status per row.
     *  Excludes items the household marked as not-mine. */
    @Query("""
        SELECT ri FROM ReceiptItem ri
        JOIN FETCH ri.receipt r
        JOIN FETCH r.household
        WHERE ri.product.id = :productId
          AND r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
        ORDER BY r.issuedAt ASC NULLS FIRST
    """)
    List<ReceiptItem> findHouseholdHistoryForProduct(@Param("productId") UUID productId,
                                                     @Param("householdId") UUID householdId);

    /** Which of the given product IDs have at least one confirmed, non-excluded purchase
     *  by the specified household — used to compute hasPriceHistory in batch. */
    @Query("""
        SELECT DISTINCT ri.product.id FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE ri.product.id IN :productIds
          AND r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
    """)
    List<UUID> findProductIdsWithHistoryForHousehold(@Param("productIds") List<UUID> productIds,
                                                     @Param("householdId") UUID householdId);

    /** All confirmed, non-excluded purchases of any product by this household, oldest first.
     *  Joins receipt + product so callers can build per-product histories without N+1. */
    @Query("""
        SELECT ri FROM ReceiptItem ri
        JOIN FETCH ri.receipt r
        JOIN FETCH ri.product p
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.product IS NOT NULL
          AND ri.excluded = false
        ORDER BY r.issuedAt ASC NULLS FIRST, ri.lineNumber ASC
    """)
    List<ReceiptItem> findConfirmedHistoryForHousehold(@Param("householdId") UUID householdId);
}
