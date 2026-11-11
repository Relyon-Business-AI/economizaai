package com.relyon.economizaai.service.consumption;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumptionForecastRulesTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ConsumptionSnoozeRepository snoozeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private CategoryRepository categoryRepository;

    private ConsumptionIntelligenceService service;
    private User user;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        var properties = new CollaborativeProperties();
        properties.getConsumption().setMinPurchasesForPrediction(3);
        properties.getConsumption().setFallbackMinPurchasesForPrediction(2);
        service = new ConsumptionIntelligenceService(receiptItemRepository, manualPurchaseRepository,
                snoozeRepository, productRepository, properties, householdProductAliasService, categoryRepository);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("forecast@economizaai.app").household(household).build();
        now = BrazilClock.nowDateTime();
        lenient().when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(any(), any())).thenReturn(List.of());
    }

    @Test
    void twoPurchaseProductsAreHiddenWhenAnyProductHasThree() {
        var coffee = product("CAFE PILAO 500G", "cafe", null);
        var salami = product("SALAME SEARA", "salame", null);
        history(purchases(coffee, 30, 20, 10), purchases(salami, 40, 20));

        var predictions = service.predict(user);

        assertEquals(List.of(coffee.getId()), predictions.stream().map(ConsumptionPredictionResponse::productId).toList());
    }

    @Test
    void fallsBackToTwoPurchasesWhenNoProductHasThree() {
        var salami = product("SALAME SEARA", "salame", null);
        var juice = product("SUCO UVA", "suco", null);
        history(purchases(salami, 40, 20), purchases(juice, 30, 15));

        var predictions = service.predict(user);

        assertEquals(2, predictions.size());
    }

    @Test
    void singlePurchaseNeverPredictsEvenWithFallback() {
        var salami = product("SALAME SEARA", "salame", null);
        history(purchases(salami, 20));

        assertTrue(service.predict(user).isEmpty());
    }

    @Test
    void predictionCarriesTypeLabelAndCategorySlugForTheIcon() {
        var categoryId = UUID.randomUUID();
        var cookie = product("BISC MARQ AMENDOIM", "biscoito", categoryId);
        var unclassified = product("ITEM SEM CATEGORIA", null, null);
        history(purchases(cookie, 30, 20, 10), purchases(unclassified, 30, 20, 10));
        when(categoryRepository.findAllById(List.of(categoryId)))
                .thenReturn(List.of(Category.builder().id(categoryId).slug("biscoitos").build()));

        var byName = service.predict(user).stream()
                .collect(Collectors.toMap(ConsumptionPredictionResponse::productName, prediction -> prediction));

        assertEquals("biscoito", byName.get("BISC MARQ AMENDOIM").typeLabel());
        assertEquals("biscoitos", byName.get("BISC MARQ AMENDOIM").categorySlug());
        assertNull(byName.get("ITEM SEM CATEGORIA").categorySlug());
    }

    @Test
    void pageOrdersByClosenessToRunOutAndCountsEveryStatus() {
        // 10-day cycles: long overdue (-100), ran out yesterday (-1), runs out in 3 days, fine for 9 days
        var longOverdue = product("LONG OVERDUE", "x", null);
        var justRanOut = product("JUST RAN OUT", "x", null);
        var runningLow = product("RUNNING LOW", "x", null);
        var fine = product("FINE", "x", null);
        history(purchases(longOverdue, 130, 120, 110), purchases(justRanOut, 31, 21, 11),
                purchases(runningLow, 27, 17, 7), purchases(fine, 21, 11, 1));

        var page = service.predictPage(user, null, 0, 30);

        assertEquals(List.of("JUST RAN OUT", "RUNNING LOW", "FINE", "LONG OVERDUE"),
                page.items().stream().map(ConsumptionPredictionResponse::productName).toList());
        assertEquals(2, page.counts().ranOut());
        assertEquals(1, page.counts().runningLow());
        assertEquals(1, page.counts().ok());
        assertEquals(4, page.counts().total());
        assertFalse(page.hasMore());
    }

    @Test
    void pageFiltersByStatusAndPaginates() {
        var first = product("FIRST", "x", null);
        var second = product("SECOND", "x", null);
        var third = product("THIRD", "x", null);
        history(purchases(first, 31, 21, 11), purchases(second, 32, 22, 12), purchases(third, 33, 23, 13));

        var firstPage = service.predictPage(user, ConsumptionPredictionResponse.Status.RAN_OUT, 0, 2);
        var secondPage = service.predictPage(user, ConsumptionPredictionResponse.Status.RAN_OUT, 1, 2);
        var runningLowOnly = service.predictPage(user, ConsumptionPredictionResponse.Status.RUNNING_LOW, 0, 2);

        assertEquals(List.of("FIRST", "SECOND"), firstPage.items().stream().map(ConsumptionPredictionResponse::productName).toList());
        assertTrue(firstPage.hasMore());
        assertEquals(List.of("THIRD"), secondPage.items().stream().map(ConsumptionPredictionResponse::productName).toList());
        assertFalse(secondPage.hasMore());
        assertTrue(runningLowOnly.items().isEmpty());
        assertEquals(3, runningLowOnly.counts().ranOut());
    }

    private Product product(String name, String genericName, UUID categoryId) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).genericName(genericName)
                .categoryId(categoryId).build();
    }

    private List<ReceiptItem> purchases(Product product, int... daysAgo) {
        var items = new ArrayList<ReceiptItem>();
        for (var days : daysAgo) {
            var receipt = Receipt.builder().id(UUID.randomUUID()).household(user.getHousehold()).user(user)
                    .issuedAt(now.minusDays(days)).build();
            items.add(ReceiptItem.builder().id(UUID.randomUUID()).receipt(receipt).product(product)
                    .lineNumber(1).rawDescription(product.getNormalizedName()).quantity(BigDecimal.ONE).build());
        }
        return items;
    }

    @SafeVarargs
    private void history(List<ReceiptItem>... perProduct) {
        var all = new ArrayList<ReceiptItem>();
        for (var items : perProduct) all.addAll(items);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(all);
    }
}
