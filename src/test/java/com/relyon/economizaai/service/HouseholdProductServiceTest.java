package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.ProductMarketPriceResponse.PriceType;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.PriceIndexService;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdProductServiceTest {

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final String CNPJ_ZAFFARI = "12345678000199";
    private static final String CNPJ_NACIONAL = "99887766000155";

    @Mock private ProductRepository productRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private HouseholdProductAliasRepository householdProductAliasRepository;
    @Mock private PriceIndexService priceIndexService;
    @Mock private WatchedMarketService watchedMarketService;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MarketNameService marketNameService;

    @InjectMocks private HouseholdProductService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("u@e")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .homeLatitude(new BigDecimal("-30"))
                .homeLongitude(new BigDecimal("-51"))
                .build();
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    // ---------------------------------------------------------- listHouseholdProducts

    @Test
    void listHouseholdProducts_aggregatesCountAndMostRecentPurchase() {
        var product = product("Leite");
        var older = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0));
        var newer = item(product, "4.50", CNPJ_NACIONAL, "Nacional",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        // Repository returns oldest -> newest; the last one seen wins as most-recent.
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(older, newer));

        var result = service.listHouseholdProducts(user, null);

        assertEquals(1, result.size());
        var row = result.get(0);
        assertEquals(2, row.timesBought());
        assertEquals(CNPJ_NACIONAL, row.lastMarketCnpj());
        assertEquals("Nacional", row.lastMarketName());
        assertEquals("Nacional", row.lastMarketFriendlyName());
        assertEquals(0, row.lastUnitPrice().compareTo(new BigDecimal("4.50")));
    }

    @Test
    void listHouseholdProducts_resolvesFriendlyNameFromOverride() {
        var product = product("Leite");
        var item = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(item));
        when(marketNameService.resolveNames(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(CNPJ_ZAFFARI, "Zaffari de casa"));

        var result = service.listHouseholdProducts(user, null);

        var row = result.get(0);
        assertEquals("Zaffari", row.lastMarketName());
        assertEquals("Zaffari de casa", row.lastMarketFriendlyName());
    }

    @Test
    void listHouseholdProducts_emptyWhenNoHistory() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of());

        assertEquals(List.of(), service.listHouseholdProducts(user, null));
    }

    @Test
    void listHouseholdProducts_skipsItemsWithoutProduct() {
        var product = product("Leite");
        var matched = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0));
        var unmatched = item(null, "9.99", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(matched, unmatched));

        var result = service.listHouseholdProducts(user, null);

        assertEquals(1, result.size());
        assertEquals(product.getId(), result.get(0).productId());
        assertEquals(1, result.get(0).timesBought());
    }

    @Test
    void listHouseholdProducts_fallsBackToConfirmedAtWhenIssuedAtNull() {
        var product = product("Leite");
        var confirmedAt = LocalDateTime.of(2026, Month.MARCH, 3, 9, 0);
        var item = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari", null);
        item.getReceipt().setConfirmedAt(confirmedAt);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(item));

        var result = service.listHouseholdProducts(user, null);

        assertEquals(confirmedAt, result.get(0).lastBoughtAt());
    }

    @Test
    void listHouseholdProducts_sortsMostRecentFirstWithUndatedLast() {
        var older = product("Arroz");
        var newer = product("Leite");
        var undated = product("Feijao");
        var undatedItem = item(undated, "3.00", CNPJ_ZAFFARI, "Zaffari", null);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                item(older, "5.00", CNPJ_ZAFFARI, "Zaffari", LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0)),
                item(newer, "4.50", CNPJ_NACIONAL, "Nacional", LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0)),
                undatedItem));

        var result = service.listHouseholdProducts(user, null);

        assertEquals(3, result.size());
        assertEquals(newer.getId(), result.get(0).productId());
        assertEquals(older.getId(), result.get(1).productId());
        assertEquals(undated.getId(), result.get(2).productId(), "no purchase date sorts last");
    }

    @Test
    void listHouseholdProducts_queryFiltersByNameCaseInsensitive() {
        var arroz = product("ARROZ BRANCO");
        var leite = product("Leite Integral");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                item(arroz, "5.00", CNPJ_ZAFFARI, "Zaffari", LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0)),
                item(leite, "4.50", CNPJ_NACIONAL, "Nacional", LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0))));

        var result = service.listHouseholdProducts(user, "arroz");

        assertEquals(1, result.size());
        assertEquals(arroz.getId(), result.get(0).productId());
    }

    @Test
    void listHouseholdProducts_queryMatchesHouseholdFriendlyName() {
        var renamed = product("GRAO FINO TIPO 1 5KG");
        var leite = product("Leite Integral");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                item(renamed, "25.00", CNPJ_ZAFFARI, "Zaffari", LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0)),
                item(leite, "4.50", CNPJ_NACIONAL, "Nacional", LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0))));
        when(householdProductAliasRepository.findAllByHouseholdIdAndProductIdIn(eq(HOUSEHOLD_ID), any()))
                .thenReturn(List.of(HouseholdProductAlias.builder()
                        .product(renamed).friendlyName("Arroz do mercado").build()));

        var result = service.listHouseholdProducts(user, "arroz");

        assertEquals(1, result.size());
        assertEquals(renamed.getId(), result.get(0).productId());
        assertEquals("Arroz do mercado", result.get(0).friendlyName());
    }

    @Test
    void listHouseholdProducts_friendlyNameNullWhenNeverRenamed() {
        var leite = product("Leite Integral");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                item(leite, "4.50", CNPJ_NACIONAL, "Nacional", LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0))));

        var result = service.listHouseholdProducts(user, null);

        assertNull(result.get(0).friendlyName());
    }

    @Test
    void listHouseholdProducts_nullQueryReturnsAll() {
        var arroz = product("Arroz");
        var leite = product("Leite");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                item(arroz, "5.00", CNPJ_ZAFFARI, "Zaffari", LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0)),
                item(leite, "4.50", CNPJ_NACIONAL, "Nacional", LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0))));

        var result = service.listHouseholdProducts(user, null);

        assertEquals(2, result.size());
    }

    // ---------------------------------------------------------- productMarkets

    @Test
    void productMarkets_unknownProduct_throws() {
        var productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class,
                () -> service.productMarkets(user, productId, false, null));
    }

    @Test
    void productMarkets_mergesOwnAndCommunitySortedByPriceAsc() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        // Own last paid at Zaffari = 5.00
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(CNPJ_ZAFFARI, location(CNPJ_ZAFFARI, "Zaffari")));
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        // Community median at Nacional = 4.00 (cheaper), and one row for Zaffari that must be ignored (own wins).
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of(
                        new MarketPriceRow(CNPJ_NACIONAL, "99887766", "Nacional",
                                new BigDecimal("4.00"), new BigDecimal("3.50"),
                                new BigDecimal("3.50"), LocalDateTime.of(2026, 1, 1, 0, 0), 5, 3L, 2.0, false),
                        new MarketPriceRow(CNPJ_ZAFFARI, "12345678", "Zaffari Community",
                                new BigDecimal("6.00"), new BigDecimal("5.50"),
                                new BigDecimal("5.50"), LocalDateTime.of(2026, 1, 1, 0, 0), 4, 3L, 1.0, false)));

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(2, result.size());
        // Sorted by price asc: Nacional (4.00) then Zaffari own (5.00).
        assertEquals(CNPJ_NACIONAL, result.get(0).cnpj());
        assertEquals(PriceType.COMMUNITY_MEDIAN, result.get(0).priceType());
        assertEquals(0, result.get(0).price().compareTo(new BigDecimal("4.00")));
        assertEquals(CNPJ_ZAFFARI, result.get(1).cnpj());
        assertEquals(PriceType.OWN_LAST, result.get(1).priceType());
        assertEquals(0, result.get(1).price().compareTo(new BigDecimal("5.00")));
    }

    @Test
    void productMarkets_communityMedianRoundedToTwoDecimals() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        // A scale-4 median (5.3350) must be rounded HALF_UP to 5.34 in the response.
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of(new MarketPriceRow(CNPJ_NACIONAL, "99887766", "Nacional",
                        new BigDecimal("5.3350"), new BigDecimal("3.50"),
                        new BigDecimal("3.50"), LocalDateTime.of(2026, 1, 1, 0, 0), 5, 3L, 2.0, false)));

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("5.34"), result.get(0).price());
        assertEquals(2, result.get(0).price().scale());
    }

    @Test
    void productMarkets_friendlyNameReflectsOverride() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of());
        when(marketNameService.resolveNames(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(CNPJ_ZAFFARI, "Zaffari de casa"));
        when(marketNameService.applyOverride(any(), eq(CNPJ_ZAFFARI), any())).thenReturn("Zaffari de casa");

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(1, result.size());
        assertEquals("Zaffari", result.get(0).marketName());
        assertEquals("Zaffari de casa", result.get(0).friendlyName());
    }

    @Test
    void productMarkets_includeNearby_passesRadiusToBestMarkets() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), eq(7.5), any()))
                .thenReturn(List.of());

        service.productMarkets(user, product.getId(), true, 7.5);

        verify(priceIndexService).bestMarkets(eq(product.getId()), anyInt(), any(), any(), eq(7.5), any());
    }

    @Test
    void productMarkets_lastPurchasePerCnpjWins() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        // Same market, oldest -> newest: the newest price must win.
        var olderItem = item(product, "6.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0));
        var newerItem = item(product, "5.25", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(olderItem, newerItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of());

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).price().compareTo(new BigDecimal("5.25")));
        assertEquals(LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0), result.get(0).observedAt());
    }

    @Test
    void productMarkets_marksWatchedAndComputesDistanceFromHome() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        var locatedMarket = MarketLocation.builder().cnpj(CNPJ_ZAFFARI).name("Zaffari")
                .latitude(new BigDecimal("-30.1")).longitude(new BigDecimal("-51.1")).build();
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(CNPJ_ZAFFARI, locatedMarket));
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of(CNPJ_ZAFFARI));
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of());

        var result = service.productMarkets(user, product.getId(), false, null);

        var row = result.get(0);
        assertTrue(row.watched());
        assertTrue(row.visited(), "own purchase marks the market as visited");
        assertNotNull(row.distanceKm());
        assertTrue(row.distanceKm() > 0 && row.distanceKm() < 30,
                "0.1 degree offset in lat/long is a small positive distance");
    }

    @Test
    void productMarkets_distanceNullWhenUserHasNoHomeCoordinates() {
        var noHomeUser = User.builder()
                .id(UUID.randomUUID())
                .email("nohome@e")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        var locatedMarket = MarketLocation.builder().cnpj(CNPJ_ZAFFARI).name("Zaffari")
                .latitude(new BigDecimal("-30.1")).longitude(new BigDecimal("-51.1")).build();
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(CNPJ_ZAFFARI, locatedMarket));
        when(watchedMarketService.watchedCnpjs(noHomeUser)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), isNull(), isNull(), isNull(), any()))
                .thenReturn(List.of());

        var result = service.productMarkets(noHomeUser, product.getId(), false, null);

        assertNull(result.get(0).distanceKm());
    }

    @Test
    void productMarkets_ownItemWithNullCnpjIsSkipped() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var noCnpjItem = item(product, "5.00", null, "Feira sem CNPJ",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(noCnpjItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of());

        assertEquals(List.of(), service.productMarkets(user, product.getId(), false, null));
    }

    @Test
    void productMarkets_nullCommunityMedianSortsLast() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        var ownItem = item(product, "5.00", CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.FEBRUARY, 1, 10, 0));
        when(receiptItemRepository.findHouseholdHistoryForProduct(product.getId(), HOUSEHOLD_ID))
                .thenReturn(List.of(ownItem));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        // k-anon-suppressed market: row present but no median price.
        when(priceIndexService.bestMarkets(eq(product.getId()), anyInt(), any(), any(), isNull(), any()))
                .thenReturn(List.of(new MarketPriceRow(CNPJ_NACIONAL, "99887766", "Nacional",
                        null, null, null, null, 2, 2L, 2.0, false)));

        var result = service.productMarkets(user, product.getId(), false, null);

        assertEquals(2, result.size());
        assertEquals(CNPJ_ZAFFARI, result.get(0).cnpj(), "priced rows come first");
        assertEquals(CNPJ_NACIONAL, result.get(1).cnpj());
        assertNull(result.get(1).price());
    }

    // ---------------------------------------------------------- helpers

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(ProductCategory.MEAT_DAIRY).build();
    }

    private ReceiptItem item(Product product, String unitPrice, String cnpj, String marketName, LocalDateTime issuedAt) {
        return ReceiptItem.builder()
                .product(product)
                .unitPrice(new BigDecimal(unitPrice))
                .receipt(Receipt.builder()
                        .cnpjEmitente(cnpj)
                        .marketName(marketName)
                        .issuedAt(issuedAt)
                        .status(ReceiptStatus.CONFIRMED)
                        .build())
                .build();
    }

    private MarketLocation location(String cnpj, String name) {
        return MarketLocation.builder().cnpj(cnpj).name(name).build();
    }
}
