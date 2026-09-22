package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface InsightsRepository extends JpaRepository<Receipt, UUID> {

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
    """)
    BigDecimal totalSpend(@Param("householdId") UUID householdId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to);

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
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)
        ORDER BY year ASC, month ASC
    """)
    List<Object[]> spendByMonth(@Param("householdId") UUID householdId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

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
    """)
    BigDecimal totalDiscount(@Param("householdId") UUID householdId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);

    @Query("""
        SELECT EXTRACT(YEAR FROM r.issuedAt) AS year,
               EXTRACT(MONTH FROM r.issuedAt) AS month,
               COALESCE(SUM(r.discountTotal), 0) AS discount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)
    """)
    List<Object[]> discountByMonth(@Param("householdId") UUID householdId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Query("""
        SELECT EXTRACT(YEAR FROM r.issuedAt) AS year,
               EXTRACT(WEEK FROM r.issuedAt) AS week,
               COALESCE(SUM(r.discountTotal), 0) AS discount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(WEEK FROM r.issuedAt)
    """)
    List<Object[]> discountByWeek(@Param("householdId") UUID householdId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @Query("""
        SELECT r.cnpjEmitente AS cnpj,
               COALESCE(SUM(r.discountTotal), 0) AS discount
        FROM Receipt r
        WHERE r.household.id = :householdId
          AND r.status = 'CONFIRMED'
          AND r.issuedAt >= :from
          AND r.issuedAt <= :to
        GROUP BY r.cnpjEmitente
    """)
    List<Object[]> discountByMarket(@Param("householdId") UUID householdId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

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
        GROUP BY r.cnpjEmitente
        ORDER BY total DESC
    """)
    List<Object[]> spendByMarket(@Param("householdId") UUID householdId,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    // COALESCE must yield the ProductCategory ENUM (the result is cast to
    // ProductCategory in InsightsService), so the null fallback needs the
    // fully-qualified enum literal — a string literal makes the projection a
    // String and breaks both the cast and Postgres enum/text unification.
    // This is the rare JPQL type-inference exception to the no-FQN rule.
    // Snapshot-first: confirmed history aggregates by the category frozen at
    // confirmation (categoryAtConfirmation), so later recategorizations never
    // rewrite what the household already saw; live p.category is only the
    // fallback for legacy rows without a snapshot.
    @Query("""
        SELECT COALESCE(ri.categoryAtConfirmation, p.category, com.relyon.economizaai.model.enums.ProductCategory.OTHER) AS category,
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
        GROUP BY COALESCE(ri.categoryAtConfirmation, p.category, com.relyon.economizaai.model.enums.ProductCategory.OTHER)
        ORDER BY total DESC
    """)
    List<Object[]> spendByCategory(@Param("householdId") UUID householdId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    // Product-granularity spend, used by the HOUSEHOLD category lens: the global
    // p.category is returned per product so InsightsService can re-bucket by the
    // household's EFFECTIVE category (override custom name / corrected enum / global
    // enum) in code, without double-counting. Items with no linked product collapse
    // under a null productId + null category (treated as OTHER downstream).
    @Query("""
        SELECT p.id AS productId,
               COALESCE(ri.categoryAtConfirmation, p.category) AS category,
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
        GROUP BY p.id, COALESCE(ri.categoryAtConfirmation, p.category)
    """)
    List<Object[]> spendByProduct(@Param("householdId") UUID householdId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

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
        SELECT EXTRACT(YEAR FROM r.issuedAt) AS year,
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
        GROUP BY EXTRACT(YEAR FROM r.issuedAt), EXTRACT(WEEK FROM r.issuedAt)
        ORDER BY year ASC, week ASC
    """)
    List<Object[]> spendByWeek(@Param("householdId") UUID householdId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);
}
