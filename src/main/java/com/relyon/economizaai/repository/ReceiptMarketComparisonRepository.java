package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ReceiptMarketComparison;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReceiptMarketComparisonRepository extends JpaRepository<ReceiptMarketComparison, UUID> {

    /** The stored comparison of a nota with its prices loaded, so it can be read outside a transaction. */
    @Query("""
        SELECT DISTINCT comparison FROM ReceiptMarketComparison comparison
        LEFT JOIN FETCH comparison.prices
        WHERE comparison.receiptId = :receiptId
    """)
    Optional<ReceiptMarketComparison> findByReceiptIdWithPrices(@Param("receiptId") UUID receiptId);

    boolean existsByReceiptId(UUID receiptId);
}
