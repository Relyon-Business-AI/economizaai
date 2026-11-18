package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.exception.ReceiptNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptMarketComparisonServiceTest {

    private static final String BOUGHT_AT = "11111111000111";
    private static final ViewerLocation HOME = new ViewerLocation(
            new BigDecimal("-30.03"), new BigDecimal("-51.21"), Source.PROFILE, "RS");

    @Mock private ReceiptRepository receiptRepository;
    @Mock private BasketComparisonService basketComparisonService;
    @Mock private ViewerLocationService viewerLocationService;
    @Mock private MarketNameService marketNameService;
    @Mock private MarketLocationService marketLocationService;
    @Captor private ArgumentCaptor<Collection<BasketLine>> basketCaptor;

    private ReceiptMarketComparisonService service;
    private Household household;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ReceiptMarketComparisonService(receiptRepository, basketComparisonService,
                viewerLocationService, marketNameService, marketLocationService);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("buyer@economizai").household(household).build();
        lenient().when(viewerLocationService.resolve(user)).thenReturn(HOME);
        lenient().when(basketComparisonService.compare(any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.resolveChainNames(any())).thenReturn(Map.of());
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        lenient().when(marketNameService.friendlyName(any(Map.class), any(Map.class), any(), any()))
                .thenAnswer(invocation -> "Amigável " + invocation.getArgument(3));
    }

    @Test
    void compare_sendsOnlyComparableLinesAtThePriceReallyPaidAndExcludesTheStoreOfTheNota() {
        var milk = product("Leite");
        var coffee = product("Cafe");
        var receipt = receipt(household,
                item(milk, "6", "31.74", null, false),
                item(coffee, "2", "37.80", "33.00", false),   // paid less than printed: the paid total counts
                item(product("Excluido"), "1", "9.00", null, true),
                item(null, "1", "5.00", null, false),          // unidentified product
                item(product("Sem preco"), "1", null, null, false));
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        var response = service.compare(user, receipt.getId());

        verify(basketComparisonService).compare(eq(HOME), basketCaptor.capture(), eq(Set.of("11111111")), eq(3));
        var basket = List.copyOf(basketCaptor.getValue());
        assertEquals(2, basket.size());
        assertEquals(new BigDecimal("31.74"), basket.get(0).lineTotal());
        assertEquals(new BigDecimal("33.00"), basket.get(1).lineTotal());
        assertEquals(2, response.comparableItemCount());
        assertTrue(response.locationKnown());
        assertTrue(response.markets().isEmpty());
    }

    @Test
    void compare_mapsEachMarketWithFriendlyNameTotalsAndItems() {
        var milk = product("Leite");
        var receipt = receipt(household, item(milk, "6", "31.74", null, false));
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        var seenAt = LocalDateTime.now().minusDays(3);
        when(basketComparisonService.compare(any(), any(), any(), anyInt())).thenReturn(List.of(
                new MarketAlternative("22222222000122", "22222222", "ATACADO SUL SA", 1.2,
                        new BigDecimal("31.74"), new BigDecimal("28.74"),
                        List.of(new AlternativeLine(milk.getId(), "Leite", new BigDecimal("6"),
                                new BigDecimal("31.74"), new BigDecimal("4.79"), new BigDecimal("28.74"), seenAt)))));

        var market = service.compare(user, receipt.getId()).markets().get(0);

        assertEquals("22222222000122", market.cnpj());
        assertEquals("Amigável ATACADO SUL SA", market.friendlyName());
        assertEquals(1, market.matchedItemCount());
        assertEquals(new BigDecimal("31.74"), market.paidTotal());
        assertEquals(new BigDecimal("28.74"), market.alternativeTotal());
        assertEquals(new BigDecimal("-3.00"), market.difference());
        assertEquals(seenAt, market.items().get(0).observedAt());
        assertEquals(new BigDecimal("4.79"), market.items().get(0).alternativeUnitPrice());
    }

    @Test
    void compare_reportsWhenThereIsNoPositionToMeasureFrom() {
        var receipt = receipt(household, item(product("Leite"), "6", "31.74", null, false));
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));
        when(viewerLocationService.resolve(user)).thenReturn(new ViewerLocation(null, null, Source.NONE, null));

        assertFalse(service.compare(user, receipt.getId()).locationKnown());
    }

    @Test
    void compare_hidesNotasOfOtherHouseholds() {
        var otherHousehold = Household.builder().id(UUID.randomUUID()).inviteCode("ZZZ999").build();
        var receipt = receipt(otherHousehold, item(product("Leite"), "6", "31.74", null, false));
        when(receiptRepository.findByIdWithItemsAndProducts(receipt.getId())).thenReturn(Optional.of(receipt));

        assertThrows(ReceiptNotFoundException.class, () -> service.compare(user, receipt.getId()));
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }

    private Receipt receipt(Household owner, ReceiptItem... items) {
        return Receipt.builder().id(UUID.randomUUID()).household(owner).cnpjEmitente(BOUGHT_AT)
                .items(new ArrayList<>(List.of(items))).build();
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
}
