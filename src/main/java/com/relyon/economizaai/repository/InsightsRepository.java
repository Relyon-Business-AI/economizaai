package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.enums.MerchantSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Personal spend aggregations for the dashboard/insights, all scoped by a
 * {@code scope} ({@code ALL} / {@code SUPPORTED} / {@code OTHER}) so the home
 * screen can show only grocery/pharmacy (our specialty) while an "Outras notas"
 * view shows the rest. The scope filters by the emitente's merchant segment via
 * the household's {@code supportedCnpjs} set (computed once in the service): a
 * receipt is SUPPORTED when its CNPJ maps to a grocery/pharmacy market, OTHER
 * otherwise (incl. unregistered / photo receipts with no CNPJ). ALL ignores it.
 */
@Repository
public interface InsightsRepository extends JpaRepository<Receipt, UUID> {

    /**
     * The year a week BELONGS to (ISO week-numbering year), so the days around New Year
     * stay in one bucket: 1–3 Jan that fall in the last week of the previous year take
     * that year, and late-December days already in week 1 take the next one. Written as
     * a portable expression (HQL has no ISO-year field) — grouping by the calendar year
     * split that week in two.
     */
    String ISO_WEEK_YEAR = """
        (EXTRACT(YEAR FROM r.issuedAt)
         + CASE WHEN EXTRACT(MONTH FROM r.issuedAt) = 1 AND EXTRACT(WEEK FROM r.issuedAt) >= 52 THEN -1
                WHEN EXTRACT(MONTH FROM r.issuedAt) = 12 AND EXTRACT(WEEK FROM r.issuedAt) = 1 THEN 1
                ELSE 0 END)""";

    // The household's CONFIRMED-receipt CNPJs that count for the lens. An admin
    // SUPPORTED override promotes a merchant stuck UNKNOWN (CNAE never resolved) to
    // "mercado suportado", a BLOCKED override excludes it even with a grocery segment,
    // otherwise it falls back to the CNAE segment. The SUPPORTED override only counts
    // for lenses that ABSORB it (:absorbsOverride — those including mercado, i.e.
    // SUPERMARKET/FOOD_RETAIL); specific lenses (PHARMACY/FUEL) must NOT inherit it,
    // else a promoted supermarket leaks into Farmácia. Used to partition every
    // aggregation below.
    @Query("""
        SELECT DISTINCT r.cnpjEmitente
        FROM Receipt r
        JOIN MarketLocation ml ON ml.cnpj = r.cnpjEmitente
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ((ml.supportOverride = 'SUPPORTED'
                AND (ml.segment IN :segments OR :absorbsOverride = TRUE))
               OR (ml.supportOverride IS NULL AND ml.segment IN :segments))
    """)
    List<String> supportedCnpjs(@Param("householdId") UUID householdId,
                                @Param("segments") Collection<MerchantSegment> segments,
                                @Param("absorbsOverride") boolean absorbsOverride);

    /**
     * Versão para a LISTAGEM de notas (não para agregações de gasto): resolve o segmento
     * da própria nota, SEM exigir CONFIRMED (uma nota em revisão já cai na lente certa) e
     * casando por CNPJ exato OU pela RAIZ da rede (uma loja nova de uma rede já classificada
     * — ex.: um Zaffari recém-escaneado — entra em Mercado na hora, sem esperar o
     * classificador por-loja). Um bloqueio explícito naquela loja exclui. Isto conserta a
     * nota de supermercado que caía em "Outras" até confirmar/classificar.
     */
    @Query("""
        SELECT DISTINCT r.cnpjEmitente
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.cnpjEmitente IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM MarketLocation blocked
                          WHERE blocked.cnpj = r.cnpjEmitente AND blocked.supportOverride = 'BLOCKED')
          AND (
            EXISTS (SELECT 1 FROM MarketLocation ex
                    WHERE ex.cnpj = r.cnpjEmitente
                      AND ((ex.supportOverride = 'SUPPORTED' AND (ex.segment IN :segments OR :absorbsOverride = TRUE))
                           OR (ex.supportOverride IS NULL AND ex.segment IN :segments)))
            OR (
              NOT EXISTS (SELECT 1 FROM MarketLocation known
                          WHERE known.cnpj = r.cnpjEmitente AND known.segment <> 'UNKNOWN')
              AND EXISTS (SELECT 1 FROM MarketLocation chain
                          WHERE chain.cnpjRoot = SUBSTRING(r.cnpjEmitente, 1, 8)
                            AND ((chain.supportOverride = 'SUPPORTED' AND (chain.segment IN :segments OR :absorbsOverride = TRUE))
                                 OR (chain.supportOverride IS NULL AND chain.segment IN :segments)))
            )
          )
    """)
    List<String> householdListingCnpjsInSegment(@Param("householdId") UUID householdId,
                                                @Param("segments") Collection<MerchantSegment> segments,
                                                @Param("absorbsOverride") boolean absorbsOverride);

    @Query("""
        SELECT COALESCE(SUM(ri.totalPrice), 0)
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
    """)
    BigDecimal totalSpend(@Param("householdId") UUID householdId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          @Param("scope") String scope,
                          @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    @Query("""
        SELECT EXTRACT(YEAR FROM r.issuedAt) AS year,
               EXTRACT(MONTH FROM r.issuedAt) AS month,
               COALESCE(SUM(ri.totalPrice), 0) AS total,
               COUNT(DISTINCT r.id) AS receiptCount
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)
        ORDER BY year ASC, month ASC
    """)
    List<Object[]> spendByMonth(@Param("householdId") UUID householdId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("scope") String scope,
                                @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    // Receipt-level discount aggregations. These query Receipt directly (NO item
    // join) so the receipt-level discountTotal is counted once per receipt, never
    // multiplied by the line-item count. Item prices stay gross everywhere; the
    // discount is reported alongside as a separate figure per displayed slice.

    @Query("""
        SELECT COALESCE(SUM(r.discountTotal), 0)
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
    """)
    BigDecimal totalDiscount(@Param("householdId") UUID householdId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             @Param("scope") String scope,
                             @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    @Query("""
        SELECT EXTRACT(YEAR FROM r.issuedAt) AS year,
               EXTRACT(MONTH FROM r.issuedAt) AS month,
               COALESCE(SUM(r.discountTotal), 0) AS discount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)
    """)
    List<Object[]> discountByMonth(@Param("householdId") UUID householdId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("scope") String scope,
                                   @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    @Query("""
        SELECT """ + ISO_WEEK_YEAR + """
                AS year,
               EXTRACT(WEEK FROM r.issuedAt) AS week,
               COALESCE(SUM(r.discountTotal), 0) AS discount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY """ + ISO_WEEK_YEAR + """
                , EXTRACT(WEEK FROM r.issuedAt)
    """)
    List<Object[]> discountByWeek(@Param("householdId") UUID householdId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  @Param("scope") String scope,
                                  @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    @Query("""
        SELECT r.cnpjEmitente AS cnpj,
               COALESCE(SUM(r.discountTotal), 0) AS discount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY r.cnpjEmitente
    """)
    List<Object[]> discountByMarket(@Param("householdId") UUID householdId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("scope") String scope,
                                    @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    @Query("""
        SELECT r.cnpjEmitente AS cnpj,
               MAX(r.marketName) AS marketName,
               COALESCE(SUM(ri.totalPrice), 0) AS total,
               COUNT(DISTINCT r.id) AS receiptCount
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY r.cnpjEmitente
        ORDER BY total DESC
    """)
    List<Object[]> spendByMarket(@Param("householdId") UUID householdId,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to,
                                 @Param("scope") String scope,
                                 @Param("supportedCnpjs") Collection<String> supportedCnpjs);


    /**
     * Per-product spend carrying the product's GLOBAL taxonomy node (category_id), so
     * InsightsService can re-bucket by the household's EFFECTIVE node — applying custom /
     * node / enum overrides in code (override-aware "Por categoria"). Null product → null node.
     */
    @Query("""
        SELECT p.id AS productId,
               p.categoryId AS categoryId,
               COALESCE(SUM(ri.totalPrice), 0) AS total,
               COUNT(ri) AS itemCount
        FROM ReceiptItem ri
        JOIN ri.receipt r
        LEFT JOIN ri.product p
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY p.id, p.categoryId
    """)
    List<Object[]> spendByProductNode(@Param("householdId") UUID householdId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("scope") String scope,
                                      @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    @Query("""
        SELECT r.issuedAt AS issuedAt,
               r.cnpjEmitente AS cnpj,
               r.marketName AS marketName,
               ri.unitPrice AS unitPrice,
               ri.quantity AS quantity
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND ri.product.id = :productId
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        ORDER BY r.issuedAt ASC
    """)
    List<Object[]> priceHistoryForProduct(@Param("householdId") UUID householdId,
                                          @Param("productId") UUID productId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    @Query("""
        SELECT """ + ISO_WEEK_YEAR + """
                AS year,
               EXTRACT(WEEK FROM r.issuedAt) AS week,
               COALESCE(SUM(ri.totalPrice), 0) AS total,
               COUNT(DISTINCT r.id) AS receiptCount
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
          AND (:scope = 'ALL'
               OR (:scope = 'SUPPORTED' AND r.cnpjEmitente IN :supportedCnpjs)
               OR (:scope = 'OTHER' AND (r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN :supportedCnpjs)))
        GROUP BY """ + ISO_WEEK_YEAR + """
                , EXTRACT(WEEK FROM r.issuedAt)
        ORDER BY year ASC, week ASC
    """)
    List<Object[]> spendByWeek(@Param("householdId") UUID householdId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("scope") String scope,
                               @Param("supportedCnpjs") Collection<String> supportedCnpjs);

    /**
     * The dashboard's spend definition ({@link #totalSpend}, every segment) for MANY
     * households at once: (householdId, total, receiptCount) by PURCHASE date. Households
     * with nothing in the window have no row. Used by the spend digests so a notification
     * never disagrees with the screen it opens.
     */
    @Query("""
        SELECT r.household.id AS householdId,
               COALESCE(SUM(ri.totalPrice), 0) AS total,
               COUNT(DISTINCT r.id) AS receiptCount
        FROM ReceiptItem ri
        JOIN ri.receipt r
        WHERE r.household.id IN :householdIds
          AND r.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.excludedFromPersonal = false
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY r.household.id
    """)
    List<Object[]> spendByHouseholds(@Param("householdIds") Collection<UUID> householdIds,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);
}
