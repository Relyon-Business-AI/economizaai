package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.ReceiptMarketComparison;
import com.relyon.economizaai.model.ReceiptMarketComparisonPrice;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptMarketComparisonRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.AlternativeLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.BasketLine;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.MarketAlternative;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptMarketComparisonServiceTest {

    private static final String BOUGHT_AT = "11111111000111";
    private static final String ATACADO = "22222222000122";
    private static final ViewerLocation HOME = new ViewerLocation(
            new BigDecimal("-30.03"), new BigDecimal("-51.21"), Source.PROFILE, "RS");

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptMarketComparisonRepository comparisonRepository;
    @Mock private BasketComparisonService basketComparisonService;
    @Mock private ViewerLocationService viewerLocationService;
    @Mock private MarketNameService marketNameService;
    @Mock private MarketLocationService marketLocationService;
    @Mock private PlatformTransactionManager transactionManager;
    @Captor private ArgumentCaptor<Collection<BasketLine>> basketCaptor;
    @Captor private ArgumentCaptor<ReceiptMarketComparison> comparisonCaptor;

    private final List<Runnable> queuedWork = new ArrayList<>();
    private ReceiptMarketComparisonService service;
    private Household household;
    private User user;
    private Product milk;
    private Product coffee;

    @BeforeEach
    void setUp() {
        Executor queue = queuedWork::add;
        service = new ReceiptMarketComparisonService(receiptRepository, comparisonRepository,
                basketComparisonService, viewerLocationService, marketNameService, marketLocationService,
                queue, transactionManager);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("buyer@economizai").household(household).build();
        milk = product("Leite");
        coffee = product("Cafe");
        lenient().when(viewerLocationService.resolve(user)).thenReturn(HOME);
        lenient().when(comparisonRepository.findByReceiptIdWithPrices(any())).thenReturn(Optional.empty());
        lenient().when(comparisonRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(basketComparisonService.compare(any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.resolveChainNames(any())).thenReturn(Map.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        lenient().when(marketNameService.friendlyName(any(Map.class), any(Map.class), any(), any()))
                .thenAnswer(invocation -> "Amigável " + invocation.getArgument(3));
    }

    @Test
    void compare_capturesTheFirstTimeAtThePriceReallyPaidAndStoresThePricesOfEachMarket() {
        var receipt = confirmedReceipt(
                item(milk, "6", "31.74", null, false),
                item(coffee, "2", "37.80", "33.00", false),   // paid less than printed: the paid total counts
                item(product("Excluido"), "1", "9.00", null, true),
                item(null, "1", "5.00", null, false));          // unidentified product
        var seenAt = LocalDateTime.now().minusDays(3);
        when(basketComparisonService.compare(any(), any(), any(), anyInt())).thenReturn(List.of(
                atacado(seenAt, line(milk, "6", "4.79", seenAt), line(coffee, "2", "16.00", seenAt))));

        var response = service.compare(user, receipt.getId());

        verify(basketComparisonService).compare(eq(HOME), basketCaptor.capture(), eq(Set.of("11111111")), eq(3));
        var basket = List.copyOf(basketCaptor.getValue());
        assertEquals(2, basket.size());
        assertEquals(new BigDecimal("33.00"), basket.get(1).lineTotal());

        verify(comparisonRepository).saveAndFlush(comparisonCaptor.capture());
        var stored = comparisonCaptor.getValue();
        assertEquals(receipt.getId(), stored.getReceiptId());
        assertEquals(2, stored.getPrices().size());
        assertEquals(new BigDecimal("4.79"), stored.getPrices().get(0).getUnitPrice());
        assertEquals(ATACADO, stored.getPrices().get(0).getMarketCnpj());

        assertTrue(response.available());
        assertEquals(2, response.comparableItemCount());
        var market = response.markets().get(0);
        assertEquals("Amigável ATACADO SUL SA", market.friendlyName());
        assertEquals(2, market.matchedItemCount());
        assertEquals(new BigDecimal("64.74"), market.paidTotal());          // 31.74 + 33.00
        assertEquals(new BigDecimal("60.74"), market.alternativeTotal());   // 6 x 4.79 + 2 x 16.00
        assertEquals(new BigDecimal("-4.00"), market.difference());
    }

    @Test
    void compare_readsTheStoredPricesWithoutPricingAgain() {
        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));
        // Bought long ago: only the stored comparison can answer.
        receipt.setIssuedAt(LocalDateTime.now().minusDays(90));
        when(comparisonRepository.findByReceiptIdWithPrices(receipt.getId()))
                .thenReturn(Optional.of(stored(receipt, price(0, milk, "4.79"))));

        var response = service.compare(user, receipt.getId());

        assertTrue(response.available());
        assertEquals(new BigDecimal("28.74"), response.markets().get(0).alternativeTotal());
        verify(basketComparisonService, never()).compare(any(), any(), any(), anyInt());
        verify(comparisonRepository, never()).saveAndFlush(any());
    }

    @Test
    void compare_followsEditsToTheNotaKeepingTheStoredPrices() {
        // Stored for milk + coffee; since then the coffee line was excluded and the milk price fixed.
        var receipt = confirmedReceipt(
                item(milk, "6", "31.74", "30.00", false),
                item(coffee, "2", "37.80", null, true));
        when(comparisonRepository.findByReceiptIdWithPrices(receipt.getId()))
                .thenReturn(Optional.of(stored(receipt, price(0, milk, "4.79"), price(0, coffee, "16.00"))));

        var market = service.compare(user, receipt.getId()).markets().get(0);

        assertEquals(1, market.matchedItemCount());
        assertEquals(new BigDecimal("30.00"), market.paidTotal());
        assertEquals(new BigDecimal("28.74"), market.alternativeTotal());
        verify(basketComparisonService, never()).compare(any(), any(), any(), anyInt());
    }

    @Test
    void compare_dropsAMarketThatNoLongerCoversFortyPercentOfTheNota() {
        var others = List.of(product("A"), product("B"), product("C"), product("D"));
        var receipt = confirmedReceipt(
                item(milk, "1", "5.00", null, false), item(others.get(0), "1", "5.00", null, false),
                item(others.get(1), "1", "5.00", null, false), item(others.get(2), "1", "5.00", null, false),
                item(others.get(3), "1", "5.00", null, false));
        // Only 1 of the 5 items has a stored price there: below 40% (2 items).
        when(comparisonRepository.findByReceiptIdWithPrices(receipt.getId()))
                .thenReturn(Optional.of(stored(receipt, price(0, milk, "4.79"))));

        assertTrue(service.compare(user, receipt.getId()).markets().isEmpty());
    }

    @Test
    void compare_isUnavailableForANotaBoughtMoreThanSevenDaysAgoOrNotConfirmed() {
        var old = confirmedReceipt(item(milk, "6", "31.74", null, false));
        old.setIssuedAt(LocalDateTime.now().minusDays(8));
        assertFalse(service.compare(user, old.getId()).available());

        var pending = confirmedReceipt(item(milk, "6", "31.74", null, false));
        pending.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
        assertFalse(service.compare(user, pending.getId()).available());

        verify(basketComparisonService, never()).compare(any(), any(), any(), anyInt());
        verify(comparisonRepository, never()).saveAndFlush(any());
    }

    @Test
    void compare_storesNothingWithoutAPositionSoALaterReadCanCapture() {
        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));
        when(viewerLocationService.resolve(user)).thenReturn(new ViewerLocation(null, null, Source.NONE, null));

        var response = service.compare(user, receipt.getId());

        assertTrue(response.available());
        assertFalse(response.locationKnown());
        assertNull(response.computedAt());
        verify(comparisonRepository, never()).saveAndFlush(any());
    }

    @Test
    void compare_returnsWhatAnotherThreadStoredWhenTheCaptureRaces() {
        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));
        doThrow(new DataIntegrityViolationException("duplicate receipt_id"))
                .when(comparisonRepository).saveAndFlush(any());
        when(comparisonRepository.findByReceiptIdWithPrices(receipt.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored(receipt, price(0, milk, "4.79"))));

        var response = service.compare(user, receipt.getId());

        assertEquals(new BigDecimal("28.74"), response.markets().get(0).alternativeTotal());
    }

    @Test
    void compare_hidesNotasOfOtherHouseholds() {
        var otherHousehold = Household.builder().id(UUID.randomUUID()).inviteCode("ZZZ999").build();
        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));
        receipt.setHousehold(otherHousehold);

        assertThrows(ReceiptNotFoundException.class, () -> service.compare(user, receipt.getId()));
    }

    @Test
    void captureAfterCommit_capturesInTheBackgroundOnce() {
        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));

        service.captureAfterCommit(receipt.getId());

        // Nothing happens on the caller's thread: the work is queued.
        verify(comparisonRepository, never()).saveAndFlush(any());
        assertEquals(1, queuedWork.size());

        queuedWork.get(0).run();
        verify(comparisonRepository).saveAndFlush(any());
    }

    @Test
    void captureAfterCommit_skipsANotaThatAlreadyHasAComparison() {
        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));
        when(comparisonRepository.existsByReceiptId(receipt.getId())).thenReturn(true);

        service.captureAfterCommit(receipt.getId());
        queuedWork.get(0).run();

        verify(comparisonRepository, never()).saveAndFlush(any());
    }

    @Test
    void captureAfterCommit_neverFailsTheCallerWhenTheQueueIsFullOrTheCaptureBreaks() {
        Executor fullQueue = work -> {
            throw new RejectedExecutionException("queue full");
        };
        var rejecting = new ReceiptMarketComparisonService(receiptRepository, comparisonRepository,
                basketComparisonService, viewerLocationService, marketNameService, marketLocationService,
                fullQueue, transactionManager);
        rejecting.captureAfterCommit(UUID.randomUUID());

        var receipt = confirmedReceipt(item(milk, "6", "31.74", null, false));
        when(basketComparisonService.compare(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("price index unavailable"));
        service.captureAfterCommit(receipt.getId());
        queuedWork.get(0).run();

        verify(comparisonRepository, never()).saveAndFlush(any());
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }

    private Receipt confirmedReceipt(ReceiptItem... items) {
        var receipt = Receipt.builder().id(UUID.randomUUID()).household(household).user(user)
                .cnpjEmitente(BOUGHT_AT).status(ReceiptStatus.CONFIRMED)
                .issuedAt(LocalDateTime.now().minusHours(2))
                .items(new ArrayList<>(List.of(items))).build();
        lenient().when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        return receipt;
    }

    private ReceiptItem item(Product product, String quantity, String totalPrice, String paidTotalPrice,
                             boolean excluded) {
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .product(product)
                .rawDescription(product == null ? "ITEM" : product.getNormalizedName())
                .quantity(new BigDecimal(quantity))
                .totalPrice(totalPrice == null ? null : new BigDecimal(totalPrice))
                .paidTotalPrice(paidTotalPrice == null ? null : new BigDecimal(paidTotalPrice))
                .excluded(excluded)
                .build();
    }

    private MarketAlternative atacado(LocalDateTime seenAt, AlternativeLine... lines) {
        return new MarketAlternative(ATACADO, "22222222", "ATACADO SUL SA", 1.2,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(lines));
    }

    private AlternativeLine line(Product product, String quantity, String unitPrice, LocalDateTime seenAt) {
        return new AlternativeLine(product.getId(), product.getNormalizedName(), new BigDecimal(quantity),
                BigDecimal.ZERO, new BigDecimal(unitPrice), BigDecimal.ZERO, seenAt);
    }

    private ReceiptMarketComparison stored(Receipt receipt, ReceiptMarketComparisonPrice... prices) {
        var comparison = ReceiptMarketComparison.builder().id(UUID.randomUUID()).receiptId(receipt.getId())
                .computedAt(OffsetDateTime.now().minusDays(30)).build();
        List.of(prices).forEach(comparison::addPrice);
        return comparison;
    }

    private ReceiptMarketComparisonPrice price(int marketPosition, Product product, String unitPrice) {
        return ReceiptMarketComparisonPrice.builder().marketPosition(marketPosition).marketCnpj(ATACADO)
                .marketName("ATACADO SUL SA").distanceKm(new BigDecimal("1.200")).productId(product.getId())
                .unitPrice(new BigDecimal(unitPrice)).observedAt(LocalDateTime.now().minusDays(31)).build();
    }
}
