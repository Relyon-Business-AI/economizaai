package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.CategoryRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketIntelServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private PriceObservationAuditRepository priceObservationAuditRepository;
    @Mock private CategoryRepository categoryRepository;
    @InjectMocks private MarketIntelService service;

    private static Category node(String slug, String namePt, Category parent, ProductCategory legacyEnum) {
        return Category.builder()
                .id(UUID.randomUUID())
                .slug(slug)
                .namePt(namePt)
                .nameEn(namePt)
                .parent(parent)
                .legacyEnum(legacyEnum)
                .build();
    }

    @Test
    void buildsRankingsAndBucketsCategorySpendByTaxonomyGroup() {
        when(priceObservationRepository.count()).thenReturn(1342L);
        when(priceObservationAuditRepository.countDistinctContributingHouseholds()).thenReturn(18L);
        var productId = UUID.randomUUID();
        when(receiptItemRepository.topProductsByScans(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{productId, "Arroz", 40L}));
        when(receiptRepository.topMarketsByScans(any(), anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{"12345678000190", "ZAFFARI", 30L, new BigDecimal("5000.00")}));
        when(receiptRepository.confirmedReceiptsByUf(anyBoolean())).thenReturn(List.<Object[]>of(
                new Object[]{UnidadeFederativa.RS, 100L, new BigDecimal("9000.00")}));

        var carnes = node("carnes", "Carnes", null, ProductCategory.MEAT_DAIRY);
        var aves = node("aves", "Aves", carnes, null);
        var mercearia = node("mercearia", "Mercearia", null, ProductCategory.GROCERIES);
        var combustivel = node("combustivel", "Combustível", null, null);
        var etanol = node("etanol", "Etanol", combustivel, null);
        when(categoryRepository.findAll()).thenReturn(List.of(carnes, aves, mercearia, combustivel, etanol));

        when(receiptItemRepository.categoryNodeSpendGlobal(anyBoolean())).thenReturn(List.<Object[]>of(
                // nó folha → grupo Carnes com a folha Aves expansível
                new Object[]{aves.getId(), ProductCategory.MEAT_DAIRY, new BigDecimal("300.00"), 5L},
                // item pré-taxonomia (nó nulo): cai no grupo default do enum legado
                new Object[]{null, ProductCategory.GROCERIES, new BigDecimal("100.00"), 10L},
                // nó folha de combustível
                new Object[]{etanol.getId(), null, new BigDecimal("80.00"), 2L},
                // sem nó e sem enum → Sem categoria
                new Object[]{null, null, new BigDecimal("50.00"), 3L}));

        var report = service.report(false);

        assertEquals(1342L, report.priceObservations());
        assertEquals("Arroz", report.topProducts().get(0).name());
        assertEquals("ZAFFARI", report.topMarkets().get(0).name());
        assertEquals("RS", report.byUf().get(0).uf());

        // grupos ordenados por gasto desc: Carnes 300 > Mercearia 100 > Combustível 80 > Sem categoria 50
        var spend = report.categorySpend();
        assertEquals(List.of("carnes", "mercearia", "combustivel", "sem-categoria"),
                spend.stream().map(group -> group.slug()).toList());
        assertEquals("Carnes", spend.get(0).name());
        // a folha aparece expansível dentro do grupo
        assertEquals("aves", spend.get(0).children().get(0).slug());
        assertEquals(new BigDecimal("300.00"), spend.get(0).children().get(0).spend());
        // fallback de enum legado não tem folha
        assertTrue(spend.get(1).children().isEmpty());
        assertEquals("etanol", spend.get(2).children().get(0).slug());
        assertEquals("Sem categoria", spend.get(3).name());
    }
}
