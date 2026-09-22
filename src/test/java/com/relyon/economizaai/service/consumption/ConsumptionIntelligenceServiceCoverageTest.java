package com.relyon.economizaai.service.consumption;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.LogManualPurchaseRequest;
import com.relyon.economizaai.dto.request.SnoozeProductRequest;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.ConsumptionSnooze;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.ManualPurchase;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage-focused companion to {@code ConsumptionIntelligenceServiceTest}.
 * Exercises the paths the original suite leaves untouched: suggested-list
 * with upcoming, snooze/unsnooze, manual-purchase logging, quantity-aware
 * ETA adjustment, manual+receipt history merge, and the disabled / empty
 * short-circuits.
 */
@ExtendWith(MockitoExtension.class)
class ConsumptionIntelligenceServiceCoverageTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ConsumptionSnoozeRepository snoozeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    private CollaborativeProperties properties;
    private ConsumptionIntelligenceService service;
    private User user;
    private Household household;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        properties.getConsumption().setMinPurchasesForPrediction(3);
        service = new ConsumptionIntelligenceService(receiptItemRepository, manualPurchaseRepository,
                snoozeRepository, productRepository, properties, householdProductAliasService);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@economizai").household(household).build();
        lenient().when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of());
        lenient().when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of());
        lenient().when(snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(any(), any())).thenReturn(List.of());
    }

    @Test
    void predict_emptyWhenNoHistoryAtAll() {
        assertTrue(service.predict(user).isEmpty());
    }

    @Test
    void predict_skipsSnoozedProducts() {
        var snoozedProduct = product("Leite");
        var now = LocalDateTime.now();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                receiptPurchase(snoozedProduct, now.minusDays(21), BigDecimal.ONE),
                receiptPurchase(snoozedProduct, now.minusDays(14), BigDecimal.ONE),
                receiptPurchase(snoozedProduct, now.minusDays(7), BigDecimal.ONE)
        ));
        when(snoozeRepository.findAllByHouseholdIdAndSnoozedUntilAfter(eq(household.getId()), any()))
                .thenReturn(List.of(snooze(snoozedProduct)));

        assertTrue(service.predict(user).isEmpty(), "snoozed product must be filtered out");
    }

    @Test
    void predict_mergesReceiptAndManualPurchasesForSameProduct() {
        var product = product("Cafe");
        var now = LocalDateTime.now();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                receiptPurchase(product, now.minusDays(21), BigDecimal.ONE),
                receiptPurchase(product, now.minusDays(14), BigDecimal.ONE)
        ));
        when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of(
                manualPurchase(product, now.minusDays(7), BigDecimal.ONE)
        ));

        var predictions = service.predict(user);

        assertEquals(1, predictions.size());
        // 3 distinct purchases (2 receipt + 1 manual) clears min-purchases gate
        assertEquals(3, predictions.get(0).sampleSize());
    }

    @Test
    void predict_skipsReceiptsWithNullIssuedAtOrOutsideLookback() {
        var product = product("Pao");
        var now = LocalDateTime.now();
        properties.getConsumption().setHistoryLookbackDays(30);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                receiptPurchase(product, null, BigDecimal.ONE),
                receiptPurchase(product, now.minusDays(90), BigDecimal.ONE),
                receiptPurchase(product, now.minusDays(20), BigDecimal.ONE),
                receiptPurchase(product, now.minusDays(10), BigDecimal.ONE)
        ));

        var predictions = service.predict(user);

        // only the two in-window purchases count → still below min of 3 → no prediction
        assertTrue(predictions.isEmpty());
    }

    @Test
    void predict_skipsManualPurchasesOutsideLookback() {
        var product = product("Suco");
        var now = LocalDateTime.now();
        properties.getConsumption().setHistoryLookbackDays(30);
        when(manualPurchaseRepository.findAllByHouseholdId(any())).thenReturn(List.of(
                manualPurchase(product, now.minusDays(200), BigDecimal.ONE),
                manualPurchase(product, now.minusDays(20), BigDecimal.ONE),
                manualPurchase(product, now.minusDays(10), BigDecimal.ONE)
        ));

        var predictions = service.predict(user);

        // only 2 in-window → below min of 3 → no prediction
        assertTrue(predictions.isEmpty());
    }

    @Test
    void predict_skipsWhenDistinctDatesBelowMinEvenIfEventCountSufficient() {
        var product = product("Agua");
        var sameDay = LocalDateTime.now().minusDays(5);
        // 3 events but all on the same date → distinct dates = 1 < min 3
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                receiptPurchase(product, sameDay, BigDecimal.ONE),
                receiptPurchase(product, sameDay, BigDecimal.ONE),
                receiptPurchase(product, sameDay, BigDecimal.ONE)
        ));

        assertTrue(service.predict(user).isEmpty());
    }

    @Test
    void predict_quantityMultiplierStretchesNextEtaWhenLastPurchaseLarger() {
        var smallProduct = product("Detergente normal");
        var bigProduct = product("Detergente atacado");
        var now = LocalDateTime.now();
        // baseline: weekly cycle, last qty equals avg → multiplier 1.0
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                receiptPurchase(smallProduct, now.minusDays(21), BigDecimal.ONE),
                receiptPurchase(smallProduct, now.minusDays(14), BigDecimal.ONE),
                receiptPurchase(smallProduct, now.minusDays(7), BigDecimal.ONE),
                // bigProduct: last purchase 3x the usual size → ETA stretches ~3x
                receiptPurchase(bigProduct, now.minusDays(21), BigDecimal.ONE),
                receiptPurchase(bigProduct, now.minusDays(14), BigDecimal.ONE),
                receiptPurchase(bigProduct, now.minusDays(7), new BigDecimal("3"))
        ));

        var predictions = service.predict(user);

        var small = predictions.stream().filter(p -> p.productId().equals(smallProduct.getId())).findFirst().orElseThrow();
        var big = predictions.stream().filter(p -> p.productId().equals(bigProduct.getId())).findFirst().orElseThrow();
        // big basket lasts longer → its adjusted interval exceeds the baseline 7-day cycle
        assertTrue(big.averageIntervalDays().compareTo(small.averageIntervalDays()) > 0,
                "larger last purchase must stretch the next-ETA interval");
    }

    @Test
    void predict_quantityMultiplierClampedAtFiveForExtremeOutlier() {
        var product = product("Arroz wholesale");
        var now = LocalDateTime.now();
        // Many small (qty 1) buys keep the average low; the final huge buy makes
        // ratio = lastQty/avgQty exceed 5.0, so the multiplier must clamp to 5.0.
        var items = new ArrayList<ReceiptItem>();
        for (int days = 70; days >= 7; days -= 7) {
            items.add(receiptPurchase(product, now.minusDays(days), BigDecimal.ONE));
        }
        // last purchase a 1000x outlier on top of ~10 unit-buys → ratio well above 5
        items.add(receiptPurchase(product, now, new BigDecimal("1000")));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prediction = service.predict(user).get(0);

        // base interval 7 days, clamped multiplier 5.0 → 35.0
        assertEquals(0, prediction.averageIntervalDays().compareTo(new BigDecimal("35.0")));
    }

    @Test
    void suggestedList_withoutUpcomingReturnsOnlyLowOrOut() {
        var lowProduct = product("Leite");
        var okProduct = product("Detergente");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any()))
                .thenReturn(lowAndOkHistory(lowProduct, okProduct));

        var suggested = service.suggestedList(user, false, 5);

        assertEquals(1, suggested.items().size());
        assertEquals(lowProduct.getId(), suggested.items().get(0).productId());
        assertNotNull(suggested.generatedAt());
    }

    @Test
    void suggestedList_withUpcomingAppendsOkItemsUpToLimit() {
        var lowProduct = product("Leite");
        var okProduct = product("Detergente");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any()))
                .thenReturn(lowAndOkHistory(lowProduct, okProduct));

        var suggested = service.suggestedList(user, true, 5);

        assertEquals(2, suggested.items().size());
        // low/out first, then upcoming OK items
        assertEquals(lowProduct.getId(), suggested.items().get(0).productId());
        assertTrue(suggested.items().stream()
                .anyMatch(p -> p.status() == ConsumptionPredictionResponse.Status.OK));
    }

    @Test
    void suggestedList_withUpcomingRespectsUpcomingLimitOfZero() {
        var lowProduct = product("Leite");
        var okProduct = product("Detergente");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any()))
                .thenReturn(lowAndOkHistory(lowProduct, okProduct));

        var suggested = service.suggestedList(user, true, 0);

        assertEquals(1, suggested.items().size());
        assertEquals(lowProduct.getId(), suggested.items().get(0).productId());
    }

    @Test
    void snooze_createsNewSnoozeWhenNoneExists() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(snoozeRepository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.snooze(user, product.getId(), new SnoozeProductRequest(14));

        var captor = ArgumentCaptor.forClass(ConsumptionSnooze.class);
        verify(snoozeRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(product.getId(), saved.getProduct().getId());
        assertTrue(saved.getSnoozedUntil().isAfter(LocalDateTime.now().plusDays(13)));
    }

    @Test
    void snooze_updatesExistingSnoozeUntil() {
        var product = product("Cafe");
        var existing = ConsumptionSnooze.builder()
                .id(UUID.randomUUID())
                .household(household)
                .product(product)
                .snoozedUntil(LocalDateTime.now().plusDays(1))
                .build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(snoozeRepository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.of(existing));

        service.snooze(user, product.getId(), new SnoozeProductRequest(30));

        verify(snoozeRepository).save(existing);
        assertTrue(existing.getSnoozedUntil().isAfter(LocalDateTime.now().plusDays(29)));
    }

    @Test
    void snooze_throwsWhenProductMissing() {
        var missingId = UUID.randomUUID();
        when(productRepository.findById(missingId)).thenReturn(Optional.empty());
        var request = new SnoozeProductRequest(7);

        assertThrows(ProductNotFoundException.class,
                () -> service.snooze(user, missingId, request));
        verify(snoozeRepository, never()).save(any());
    }

    @Test
    void unsnooze_deletesSnoozeForProduct() {
        var productId = UUID.randomUUID();

        service.unsnooze(user, productId);

        verify(snoozeRepository).deleteByHouseholdIdAndProductId(household.getId(), productId);
    }

    @Test
    void logManualPurchase_savesPurchaseAndClearsSnooze() {
        var product = product("Acucar");
        var purchasedAt = LocalDateTime.now().minusDays(2);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        service.logManualPurchase(user,
                new LogManualPurchaseRequest(product.getId(), new BigDecimal("2"), purchasedAt));

        var captor = ArgumentCaptor.forClass(ManualPurchase.class);
        verify(manualPurchaseRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(product.getId(), saved.getProduct().getId());
        assertEquals(0, saved.getQuantity().compareTo(new BigDecimal("2")));
        assertEquals(purchasedAt, saved.getPurchasedAt());
        verify(snoozeRepository).deleteByHouseholdIdAndProductId(household.getId(), product.getId());
    }

    @Test
    void logManualPurchase_defaultsPurchasedAtToNowWhenAbsent() {
        var product = product("Sal");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var before = LocalDateTime.now().minusSeconds(1);

        service.logManualPurchase(user,
                new LogManualPurchaseRequest(product.getId(), BigDecimal.ONE, null));

        var captor = ArgumentCaptor.forClass(ManualPurchase.class);
        verify(manualPurchaseRepository).save(captor.capture());
        assertFalse(captor.getValue().getPurchasedAt().isBefore(before));
    }

    @Test
    void logManualPurchase_throwsWhenProductMissing() {
        var missingId = UUID.randomUUID();
        when(productRepository.findById(missingId)).thenReturn(Optional.empty());
        var request = new LogManualPurchaseRequest(missingId, BigDecimal.ONE, null);

        assertThrows(ProductNotFoundException.class,
                () -> service.logManualPurchase(user, request));
        verify(manualPurchaseRepository, never()).save(any());
    }

    private List<ReceiptItem> lowAndOkHistory(Product lowProduct, Product okProduct) {
        var now = LocalDateTime.now();
        var items = new ArrayList<ReceiptItem>();
        // weekly cycle, last 7 days ago → RUNNING_LOW
        for (int days : new int[]{28, 21, 14, 7}) {
            items.add(receiptPurchase(lowProduct, now.minusDays(days), BigDecimal.ONE));
        }
        // monthly cycle, just bought → OK
        for (int days : new int[]{90, 60, 30, 0}) {
            items.add(receiptPurchase(okProduct, now.minusDays(days), BigDecimal.ONE));
        }
        return items;
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }

    private ConsumptionSnooze snooze(Product product) {
        return ConsumptionSnooze.builder()
                .id(UUID.randomUUID())
                .household(household)
                .product(product)
                .snoozedUntil(LocalDateTime.now().plusDays(10))
                .build();
    }

    private ReceiptItem receiptPurchase(Product product, LocalDateTime issuedAt, BigDecimal quantity) {
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
                .quantity(quantity)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(quantity)
                .build();
    }

    private ManualPurchase manualPurchase(Product product, LocalDateTime purchasedAt, BigDecimal quantity) {
        return ManualPurchase.builder()
                .id(UUID.randomUUID())
                .household(household)
                .user(user)
                .product(product)
                .quantity(quantity)
                .purchasedAt(purchasedAt)
                .build();
    }
}
