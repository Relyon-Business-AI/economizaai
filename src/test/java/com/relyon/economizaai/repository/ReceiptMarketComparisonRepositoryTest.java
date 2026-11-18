package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ReceiptMarketComparison;
import com.relyon.economizaai.model.ReceiptMarketComparisonPrice;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class ReceiptMarketComparisonRepositoryTest {

    @Autowired private ReceiptMarketComparisonRepository comparisonRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void storesTheComparisonWithItsPricesAndFindsItByReceipt() {
        var receiptId = UUID.randomUUID();
        var comparison = ReceiptMarketComparison.builder().receiptId(receiptId).computedAt(OffsetDateTime.now()).build();
        comparison.addPrice(ReceiptMarketComparisonPrice.builder()
                .marketPosition(0).marketCnpj("22222222000122").marketName("Atacado Sul")
                .distanceKm(new BigDecimal("1.200")).productId(UUID.randomUUID())
                .unitPrice(new BigDecimal("4.7900")).observedAt(LocalDateTime.now().minusDays(2)).build());
        comparisonRepository.saveAndFlush(comparison);
        entityManager.clear();

        var found = comparisonRepository.findByReceiptIdWithPrices(receiptId).orElseThrow();
        entityManager.clear();

        // Prices come loaded: the comparison is read outside a transaction.
        assertEquals(1, found.getPrices().size());
        assertEquals(0, found.getPrices().get(0).getUnitPrice().compareTo(new BigDecimal("4.79")));
        assertTrue(comparisonRepository.existsByReceiptId(receiptId));
        assertFalse(comparisonRepository.existsByReceiptId(UUID.randomUUID()));
    }
}
