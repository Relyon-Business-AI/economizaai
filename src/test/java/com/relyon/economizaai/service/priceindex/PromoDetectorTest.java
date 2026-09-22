package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromoDetectorTest {

    @Mock private ReceiptItemRepository receiptItemRepository;

    private final CollaborativeProperties properties = new CollaborativeProperties();

    @InjectMocks private PromoDetector detector;

    @BeforeEach
    void setUp() {
        // need to set up the real properties (Mockito won't @InjectMocks a non-mock that easily)
        detector = new PromoDetector(receiptItemRepository, properties);
    }

    private Receipt buildReceipt(Household household) {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
        return Receipt.builder().id(UUID.randomUUID()).user(user).household(household).build();
    }

    private ReceiptItem itemWithProduct(Receipt receipt, Product product, BigDecimal unitPrice) {
        var item = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(receipt)
                .lineNumber(1)
                .rawDescription("X")
                .quantity(BigDecimal.ONE)
                .unitPrice(unitPrice)
                .totalPrice(unitPrice)
                .product(product)
                .build();
        receipt.getItems().add(item);
        return item;
    }

    private ReceiptItem historicalItem(Household household, Product product, BigDecimal unitPrice) {
        var pastReceipt = Receipt.builder()
                .id(UUID.randomUUID())
                .household(household)
                .status(ReceiptStatus.CONFIRMED)
                .build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(pastReceipt)
                .product(product)
                .unitPrice(unitPrice)
                .quantity(BigDecimal.ONE)
                .totalPrice(unitPrice)
                .lineNumber(1)
                .rawDescription("X")
                .build();
    }

    @Test
    void flagsPromoWhenPriceWellBelowHistoricalMedian() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("X").build();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        var receipt = buildReceipt(household);
        var currentItem = itemWithProduct(receipt, product, new BigDecimal("20.00"));

        // historical median = 28.00. 20 is ~28% below → above the 10% threshold.
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), household.getId()))
                .thenReturn(List.of(
                        historicalItem(household, product, new BigDecimal("28.00")),
                        historicalItem(household, product, new BigDecimal("28.00")),
                        historicalItem(household, product, new BigDecimal("28.00"))
                ));

        var promos = detector.detectPersonalPromos(receipt);
        assertEquals(1, promos.size());
        assertEquals(currentItem.getId(), promos.get(0).receiptItemId());
        assertTrue(promos.get(0).savingsPct().compareTo(new BigDecimal("20")) > 0);
    }

    @Test
    void doesNotFlagWhenBaselineTooSmall() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("X").build();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        var receipt = buildReceipt(household);
        itemWithProduct(receipt, product, new BigDecimal("1.00"));

        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), household.getId()))
                .thenReturn(List.of(historicalItem(household, product, new BigDecimal("100.00")))); // only 1 prior

        assertEquals(0, detector.detectPersonalPromos(receipt).size());
    }

    @Test
    void doesNotFlagWhenWithinThreshold() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("X").build();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        var receipt = buildReceipt(household);
        itemWithProduct(receipt, product, new BigDecimal("27.50")); // ~2% below median 28 — within 10% threshold

        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), household.getId()))
                .thenReturn(List.of(
                        historicalItem(household, product, new BigDecimal("28.00")),
                        historicalItem(household, product, new BigDecimal("28.00")),
                        historicalItem(household, product, new BigDecimal("28.00"))
                ));

        assertEquals(0, detector.detectPersonalPromos(receipt).size());
    }
}
