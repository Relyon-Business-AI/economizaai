package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketIntelServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private PriceObservationAuditRepository priceObservationAuditRepository;
    @InjectMocks private MarketIntelService service;

    @Test
    void buildsRankingsAndSortsCategorySpend() {
        when(priceObservationRepository.count()).thenReturn(1342L);
        when(priceObservationAuditRepository.countDistinctContributingHouseholds()).thenReturn(18L);
        var productId = UUID.randomUUID();
        when(receiptItemRepository.topProductsByScans(any())).thenReturn(List.<Object[]>of(
                new Object[]{productId, "Arroz", 40L}));
        when(receiptRepository.topMarketsByScans(any())).thenReturn(List.<Object[]>of(
                new Object[]{"12345678000190", "ZAFFARI", 30L, new BigDecimal("5000.00")}));
        when(receiptItemRepository.categorySpendGlobal()).thenReturn(List.<Object[]>of(
                new Object[]{ProductCategory.GROCERIES, new BigDecimal("100.00"), 10L},
                new Object[]{ProductCategory.MEAT_DAIRY, new BigDecimal("300.00"), 5L},
                new Object[]{null, new BigDecimal("50.00"), 3L}));
        when(receiptRepository.confirmedReceiptsByUf()).thenReturn(List.<Object[]>of(
                new Object[]{UnidadeFederativa.RS, 100L, new BigDecimal("9000.00")}));

        var report = service.report();

        assertEquals(1342L, report.priceObservations());
        assertEquals(18L, report.contributingHouseholds());
        assertEquals("Arroz", report.topProducts().get(0).name());
        assertEquals(40L, report.topProducts().get(0).scans());
        assertEquals("ZAFFARI", report.topMarkets().get(0).name());
        // category spend sorted by spend desc: MEAT_DAIRY (300) > GROCERIES (100) > UNCATEGORIZED (50)
        assertEquals("MEAT_DAIRY", report.categorySpend().get(0).category());
        assertEquals("UNCATEGORIZED", report.categorySpend().get(2).category());
        assertEquals("RS", report.byUf().get(0).uf());
    }
}
