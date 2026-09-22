package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.DealSurfaceState;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationEventService.RecordContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsAttributionServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String CNPJ = "12345678000190";

    @Mock private DealSurfaceStateRepository surfaceStateRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationEventService eventService;
    @Mock private CollaborativeProperties properties;

    @InjectMocks private SavingsAttributionService service;

    private User buyer;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        buyer = User.builder().id(UUID.randomUUID()).name("John").email("john@test.com").household(household).build();
        var attribution = new CollaborativeProperties.Attribution();
        attribution.setWindowDays(14);
        lenient().when(properties.getAttribution()).thenReturn(attribution);
        lenient().when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(buyer));
    }

    @Test
    void recordsConvertedEventWithPositiveSavingsAndStampsSurfaceRow() {
        var receipt = receiptWithItem(new BigDecimal("8.00"), new BigDecimal("2"));
        var surface = surfaceRow(OffsetDateTime.now().minusDays(2), new BigDecimal("10.00"));
        when(surfaceStateRepository.findAttributable(anyList(), eq(PRODUCT_ID), eq(CNPJ), any()))
                .thenReturn(List.of(surface));
        // Prior purchase at 10.00 → savings = (10.00 - 8.00) * 2 = 4.00.
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(PRODUCT_ID), any()))
                .thenReturn(List.of(priorPurchase(new BigDecimal("10.00"), LocalDateTime.now().minusDays(20))));

        service.attribute(receipt);

        var typeCaptor = ArgumentCaptor.forClass(NotificationEventType.class);
        var contextCaptor = ArgumentCaptor.forClass(RecordContext.class);
        verify(eventService).record(eq(buyer), typeCaptor.capture(), contextCaptor.capture());
        assertEquals(NotificationEventType.CONVERTED, typeCaptor.getValue());
        assertEquals(0, contextCaptor.getValue().savingsAmount().compareTo(new BigDecimal("4.00")));
        assertEquals(PRODUCT_ID, contextCaptor.getValue().productId());

        verify(surfaceStateRepository).save(surface);
        assertNotNull(surface.getConvertedAt());
    }

    @Test
    void fallsBackToSurfaceBaselineWhenNoPriorPurchase() {
        var receipt = receiptWithItem(new BigDecimal("8.00"), new BigDecimal("1"));
        var surface = surfaceRow(OffsetDateTime.now().minusDays(1), new BigDecimal("12.00"));
        when(surfaceStateRepository.findAttributable(anyList(), eq(PRODUCT_ID), eq(CNPJ), any()))
                .thenReturn(List.of(surface));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(PRODUCT_ID), any()))
                .thenReturn(List.of());

        service.attribute(receipt);

        var contextCaptor = ArgumentCaptor.forClass(RecordContext.class);
        verify(eventService).record(eq(buyer), eq(NotificationEventType.CONVERTED), contextCaptor.capture());
        // (12.00 baseline - 8.00 paid) * 1 = 4.00
        assertEquals(0, contextCaptor.getValue().savingsAmount().compareTo(new BigDecimal("4.00")));
    }

    @Test
    void noSurfaceRow_recordsNothing() {
        var receipt = receiptWithItem(new BigDecimal("8.00"), new BigDecimal("1"));
        when(surfaceStateRepository.findAttributable(anyList(), eq(PRODUCT_ID), eq(CNPJ), any()))
                .thenReturn(List.of());

        service.attribute(receipt);

        verify(eventService, never()).record(any(), any(), any());
        verify(surfaceStateRepository, never()).save(any());
    }

    @Test
    void surfaceRowOutsideWindow_recordsNothing() {
        // The repository query enforces the window; an outside-window surfacing simply
        // isn't returned, so attribution finds nothing.
        var receipt = receiptWithItem(new BigDecimal("8.00"), new BigDecimal("1"));
        when(surfaceStateRepository.findAttributable(anyList(), eq(PRODUCT_ID), eq(CNPJ), any()))
                .thenReturn(List.of());

        service.attribute(receipt);

        verify(eventService, never()).record(any(), any(), any());
    }

    @Test
    void paidNotBelowPrevious_recordsNothing() {
        var receipt = receiptWithItem(new BigDecimal("10.00"), new BigDecimal("1"));
        var surface = surfaceRow(OffsetDateTime.now().minusDays(1), new BigDecimal("9.00"));
        when(surfaceStateRepository.findAttributable(anyList(), eq(PRODUCT_ID), eq(CNPJ), any()))
                .thenReturn(List.of(surface));
        // Prior paid 10.00, now paying 10.00 → no savings.
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(PRODUCT_ID), any()))
                .thenReturn(List.of(priorPurchase(new BigDecimal("10.00"), LocalDateTime.now().minusDays(20))));

        service.attribute(receipt);

        verify(eventService, never()).record(any(), any(), any());
        verify(surfaceStateRepository, never()).save(any());
    }

    @Test
    void usesLatestPriorPurchaseAndIgnoresThisReceipt() {
        var receipt = receiptWithItem(new BigDecimal("7.00"), new BigDecimal("1"));
        var surface = surfaceRow(OffsetDateTime.now().minusDays(1), new BigDecimal("99.00"));
        when(surfaceStateRepository.findAttributable(anyList(), eq(PRODUCT_ID), eq(CNPJ), any()))
                .thenReturn(List.of(surface));
        // Two prior purchases + a row that is THIS receipt's date (must be ignored).
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(PRODUCT_ID), any()))
                .thenReturn(List.of(
                        priorPurchase(new BigDecimal("12.00"), LocalDateTime.now().minusDays(30)),
                        priorPurchase(new BigDecimal("9.00"), LocalDateTime.now().minusDays(5)),
                        priorPurchase(new BigDecimal("7.00"), receipt.getIssuedAt())));

        service.attribute(receipt);

        var contextCaptor = ArgumentCaptor.forClass(RecordContext.class);
        verify(eventService).record(eq(buyer), eq(NotificationEventType.CONVERTED), contextCaptor.capture());
        // Latest STRICTLY-prior price is 9.00 → (9.00 - 7.00) * 1 = 2.00 (not the surface baseline 99).
        assertEquals(0, contextCaptor.getValue().savingsAmount().compareTo(new BigDecimal("2.00")));
    }

    @Test
    void excludedItem_recordsNothing() {
        var receipt = receiptWithItem(new BigDecimal("8.00"), new BigDecimal("1"));
        receipt.getItems().get(0).setExcluded(true);

        service.attribute(receipt);

        verify(surfaceStateRepository, never()).findAttributable(any(), any(), any(), any());
        verify(eventService, never()).record(any(), any(), any());
    }

    private Receipt receiptWithItem(BigDecimal paidUnitPrice, BigDecimal quantity) {
        var product = Product.builder().id(PRODUCT_ID).normalizedName("ARROZ 5KG").build();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .user(buyer)
                .household(buyer.getHousehold())
                .cnpjEmitente(CNPJ)
                .issuedAt(LocalDateTime.now())
                .build();
        receipt.addItem(ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription("ARROZ 5KG")
                .product(product)
                .quantity(quantity)
                .unit("UN")
                .unitPrice(paidUnitPrice)
                .totalPrice(paidUnitPrice.multiply(quantity))
                .build());
        return receipt;
    }

    private DealSurfaceState surfaceRow(OffsetDateTime surfacedAt, BigDecimal lastUnitPrice) {
        return DealSurfaceState.builder()
                .id(UUID.randomUUID())
                .userId(buyer.getId())
                .productId(PRODUCT_ID)
                .marketCnpj(CNPJ)
                .lastUnitPrice(lastUnitPrice)
                .lastSurfacedAt(surfacedAt)
                .build();
    }

    private ReceiptItem priorPurchase(BigDecimal unitPrice, LocalDateTime issuedAt) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .household(buyer.getHousehold())
                .issuedAt(issuedAt)
                .build();
        var item = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription("ARROZ 5KG")
                .quantity(BigDecimal.ONE)
                .unitPrice(unitPrice)
                .totalPrice(unitPrice)
                .build();
        receipt.addItem(item);
        return item;
    }
}
