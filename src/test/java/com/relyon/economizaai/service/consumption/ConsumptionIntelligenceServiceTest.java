package com.relyon.economizaai.service.consumption;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumptionIntelligenceServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ConsumptionSnoozeRepository snoozeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    private CollaborativeProperties properties;
    private ConsumptionIntelligenceService service;
    private User user;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        // FE alignment: keep min-purchases at 3 for these tests so the
        // existing test data (designed around N=3) still represents
        // medium-history behavior. Default is now 2 per PRO-50.
        properties.getConsumption().setMinPurchasesForPrediction(3);
        service = new ConsumptionIntelligenceService(receiptItemRepository, manualPurchaseRepository,
                snoozeRepository, productRepository, properties, householdProductAliasService);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
        lenient().when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(any(), any())).thenReturn(List.of());
    }

    @Test
    void predict_returnsEmptyWhenDisabled() {
        properties.getConsumption().setEnabled(false);
        assertTrue(service.predict(user).isEmpty());
    }

    @Test
    void predict_skipsProductsBelowMinPurchases() {
        var product = product("Leite");
        // only 2 purchases — below default min of 3
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                purchase(product, LocalDateTime.now().minusDays(14), BigDecimal.ONE),
                purchase(product, LocalDateTime.now().minusDays(7), BigDecimal.ONE)
        ));

        assertTrue(service.predict(user).isEmpty(), "must not predict with insufficient samples");
    }

    @Test
    void predict_returnsPredictionWithAvgIntervalAndStatus() {
        var product = product("Leite");
        // 4 purchases, 7 days apart on average — last one 7 days ago → next purchase ~today → RUNNING_LOW
        var now = LocalDateTime.now();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                purchase(product, now.minusDays(28), BigDecimal.ONE),
                purchase(product, now.minusDays(21), BigDecimal.ONE),
                purchase(product, now.minusDays(14), BigDecimal.ONE),
                purchase(product, now.minusDays(7), BigDecimal.ONE)
        ));

        var predictions = service.predict(user);

        assertEquals(1, predictions.size());
        var p = predictions.get(0);
        assertEquals(product.getId(), p.productId());
        assertEquals(4, p.sampleSize());
        assertEquals(0, p.averageIntervalDays().compareTo(new BigDecimal("7.0")));
        assertEquals(ConsumptionPredictionResponse.Status.RUNNING_LOW, p.status());
        assertEquals(ConsumptionPredictionResponse.Confidence.LOW, p.confidence());
    }

    @Test
    void predict_classifiesPastDueAsRanOut() {
        var product = product("Cafe");
        var now = LocalDateTime.now();
        // 7-day cycle but last purchase 30 days ago → should be RAN_OUT
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                purchase(product, now.minusDays(50), BigDecimal.ONE),
                purchase(product, now.minusDays(43), BigDecimal.ONE),
                purchase(product, now.minusDays(36), BigDecimal.ONE),
                purchase(product, now.minusDays(30), BigDecimal.ONE)
        ));

        var predictions = service.predict(user);

        assertEquals(1, predictions.size());
        assertEquals(ConsumptionPredictionResponse.Status.RAN_OUT, predictions.get(0).status());
        assertTrue(predictions.get(0).daysUntilNextPurchase() < 0);
    }

    @Test
    void predict_classifiesFarFutureAsOk() {
        var product = product("Detergente");
        var now = LocalDateTime.now();
        // 30-day cycle, just bought today → next purchase ~30 days out → OK
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                purchase(product, now.minusDays(90), BigDecimal.ONE),
                purchase(product, now.minusDays(60), BigDecimal.ONE),
                purchase(product, now.minusDays(30), BigDecimal.ONE),
                purchase(product, now, BigDecimal.ONE)
        ));

        var predictions = service.predict(user);

        assertEquals(1, predictions.size());
        assertEquals(ConsumptionPredictionResponse.Status.OK, predictions.get(0).status());
    }

    @Test
    void predict_assignsHigherConfidenceWithMoreSamples() {
        var product = product("Pao");
        var now = LocalDateTime.now();
        var items = new ArrayList<ReceiptItem>();
        for (int i = 8; i >= 0; i--) {
            items.add(purchase(product, now.minusDays(i * 3L), BigDecimal.ONE));
        }
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var predictions = service.predict(user);

        assertEquals(1, predictions.size());
        assertEquals(ConsumptionPredictionResponse.Confidence.HIGH, predictions.get(0).confidence());
    }

    @Test
    void suggestedList_includesOnlyRanOutAndRunningLow() {
        var lowProduct = product("Leite");
        var okProduct = product("Detergente");
        var now = LocalDateTime.now();
        var items = new ArrayList<ReceiptItem>();
        // Leite: weekly, last 7 days ago → RUNNING_LOW
        for (int d : new int[]{28, 21, 14, 7}) items.add(purchase(lowProduct, now.minusDays(d), BigDecimal.ONE));
        // Detergente: monthly, just bought → OK (must NOT appear in suggested list)
        for (int d : new int[]{90, 60, 30, 0}) items.add(purchase(okProduct, now.minusDays(d), BigDecimal.ONE));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var suggested = service.suggestedList(user, false, 5);

        assertEquals(1, suggested.items().size());
        assertEquals(lowProduct.getId(), suggested.items().get(0).productId());
        assertFalse(suggested.items().stream()
                .anyMatch(p -> p.status() == ConsumptionPredictionResponse.Status.OK));
    }

    @Test
    void suggestedList_negativeUpcomingLimitDoesNotThrow() {
        var okProduct = product("Detergente");
        var now = LocalDateTime.now();
        var items = new ArrayList<ReceiptItem>();
        // Detergente: monthly, just bought → OK → flows into the limit(upcomingLimit) branch
        for (int d : new int[]{90, 60, 30, 0}) items.add(purchase(okProduct, now.minusDays(d), BigDecimal.ONE));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        // A client passing ?includeUpcoming=true&upcomingLimit=-1 must not blow up with
        // IllegalArgumentException: -1 from Stream.limit(-1).
        var suggested = service.suggestedList(user, true, -1);

        assertTrue(suggested.items().isEmpty());
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }

    private ReceiptItem purchase(Product product, LocalDateTime issuedAt, BigDecimal qty) {
        var household = user.getHousehold();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .household(household)
                .user(user)
                .issuedAt(issuedAt)
                .build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(receipt)
                .product(product)
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(qty)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(qty)
                .build();
    }

    @Test
    void predict_carriesHouseholdFriendlyName() {
        var product = product("Leite");
        var now = LocalDateTime.now();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                purchase(product, now.minusDays(28), BigDecimal.ONE),
                purchase(product, now.minusDays(21), BigDecimal.ONE),
                purchase(product, now.minusDays(14), BigDecimal.ONE),
                purchase(product, now.minusDays(7), BigDecimal.ONE)
        ));
        when(householdProductAliasService.friendlyNamesFor(any(), any()))
                .thenReturn(Map.of(product.getId(), "Leite integral"));

        var predictions = service.predict(user);

        assertEquals("Leite integral", predictions.get(0).friendlyDescription());
    }
}
