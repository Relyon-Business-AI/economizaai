package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.CommunityPriceChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommunityPriceChangeRepository extends JpaRepository<CommunityPriceChange, UUID> {

    /** A price usable by the community index, as read from the anonymous observations. */
    interface IndexObservation {
        UUID getProductId();
        String getMarketCnpj();
        String getState();
        String getNormalizedUnit();
        BigDecimal getNormalizedUnitPrice();
        BigDecimal getUnitPrice();
        BigDecimal getQuantity();
        LocalDateTime getObservedAt();
    }

    @Modifying
    @Query("DELETE FROM CommunityPriceChange priceChange WHERE priceChange.referenceMonth = :referenceMonth")
    int deleteMonth(@Param("referenceMonth") LocalDate referenceMonth);

    /** In-store, non-outlier prices with a known state, of products in the given category nodes. */
    @Query("""
            SELECT observation.product.id AS productId, observation.marketCnpj AS marketCnpj,
                   observation.state AS state, observation.normalizedUnit AS normalizedUnit,
                   observation.normalizedUnitPrice AS normalizedUnitPrice, observation.unitPrice AS unitPrice,
                   observation.quantity AS quantity, observation.observedAt AS observedAt
            FROM PriceObservation observation
            WHERE observation.outlier = false
              AND observation.channel = 'IN_STORE'
              AND observation.state IS NOT NULL
              AND observation.product.categoryId IN :categoryIds
              AND observation.observedAt >= :fromInclusive
              AND observation.observedAt < :toExclusive
            """)
    List<IndexObservation> findIndexObservations(@Param("categoryIds") Collection<UUID> categoryIds,
                                                 @Param("fromInclusive") LocalDateTime fromInclusive,
                                                 @Param("toExclusive") LocalDateTime toExclusive);

    /** Per month and state: (referenceMonth, state, Σ w·ln r, Σ w). */
    @Query("""
            SELECT priceChange.referenceMonth, priceChange.state, SUM(priceChange.weightedLogSum), SUM(priceChange.weightSum)
            FROM CommunityPriceChange priceChange
            WHERE priceChange.referenceMonth BETWEEN :fromMonth AND :toMonth
            GROUP BY priceChange.referenceMonth, priceChange.state
            """)
    List<Object[]> monthlyTotalsByState(@Param("fromMonth") LocalDate fromMonth, @Param("toMonth") LocalDate toMonth);

    /** Per month and category node: (referenceMonth, categoryId, Σ w·ln r, Σ w, pairs) — one state or all of them. */
    @Query("""
            SELECT priceChange.referenceMonth, product.categoryId, SUM(priceChange.weightedLogSum), SUM(priceChange.weightSum),
                   SUM(priceChange.pairCount)
            FROM CommunityPriceChange priceChange, Product product
            WHERE product.id = priceChange.productId
              AND product.categoryId IS NOT NULL
              AND priceChange.referenceMonth BETWEEN :fromMonth AND :toMonth
              AND (:everyState = TRUE OR priceChange.state = :state)
            GROUP BY priceChange.referenceMonth, product.categoryId
            """)
    List<Object[]> monthlyTotalsByCategory(@Param("fromMonth") LocalDate fromMonth, @Param("toMonth") LocalDate toMonth,
                                           @Param("everyState") boolean everyState, @Param("state") String state);

    /** Per month and food name: (referenceMonth, genericName, Σ w·ln r, Σ w, pairs) — one state or all of them. */
    @Query("""
            SELECT priceChange.referenceMonth, product.genericName, SUM(priceChange.weightedLogSum), SUM(priceChange.weightSum),
                   SUM(priceChange.pairCount)
            FROM CommunityPriceChange priceChange, Product product
            WHERE product.id = priceChange.productId
              AND product.genericName IS NOT NULL
              AND priceChange.referenceMonth BETWEEN :fromMonth AND :toMonth
              AND (:everyState = TRUE OR priceChange.state = :state)
            GROUP BY priceChange.referenceMonth, product.genericName
            """)
    List<Object[]> monthlyTotalsByFood(@Param("fromMonth") LocalDate fromMonth, @Param("toMonth") LocalDate toMonth,
                                       @Param("everyState") boolean everyState, @Param("state") String state);

    List<CommunityPriceChange> findAllByProductIdInAndReferenceMonthBetween(Collection<UUID> productIds,
                                                                            LocalDate fromMonth, LocalDate toMonth);

    /**
     * What a household spent on each product (confirmed notas, lines that count for the
     * household) in a period: (productId, spend).
     */
    @Query("""
            SELECT item.product.id, SUM(item.totalPrice)
            FROM ReceiptItem item
            JOIN item.receipt receipt
            WHERE receipt.household.id = :householdId
              AND receipt.status = 'CONFIRMED'
              AND item.excluded = false
              AND item.excludedFromPersonal = false
              AND item.product IS NOT NULL
              AND receipt.issuedAt >= :fromInclusive
              AND receipt.issuedAt < :toExclusive
            GROUP BY item.product.id
            """)
    List<Object[]> householdSpendByProduct(@Param("householdId") UUID householdId,
                                           @Param("fromInclusive") LocalDateTime fromInclusive,
                                           @Param("toExclusive") LocalDateTime toExclusive);
}
