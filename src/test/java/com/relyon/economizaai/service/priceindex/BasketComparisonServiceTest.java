package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.priceindex.BasketComparisonService.BasketLine;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BasketComparisonServiceTest {

    private static final String ATACADO = "22222222";
    private static final String HIPER = "33333333";
    private static final ViewerLocation HOME = new ViewerLocation(
            new BigDecimal("-30.03"), new BigDecimal("-51.21"), Source.PROFILE, "RS");

    @Mock private PriceIndexService priceIndexService;

    private BasketComparisonService service;
    private UUID milk;
    private UUID coffee;
    private UUID rice;
    private UUID soap;

    @BeforeEach
    void setUp() {
        service = new BasketComparisonService(priceIndexService);
        milk = UUID.randomUUID();
        coffee = UUID.randomUUID();
        rice = UUID.randomUUID();
        soap = UUID.randomUUID();
    }

    @Test
    void compare_pricesOnlyTheItemsTheMarketHasAndKeepsTotalsLikeForLike() {
        pricedAt(milk, row(ATACADO, "4.79", 1.2));
        pricedAt(coffee, row(ATACADO, "16.49", 1.2));
        pricedAt(rice, row(ATACADO, "25.90", 1.2));
        // soap has no price at the other market: it must not enter either total.
        var basket = List.of(line(milk, "6", "31.74"), line(coffee, "2", "37.80"),
                line(rice, "1", "27.90"), line(soap, "1", "23.90"));

        var alternatives = service.compare(HOME, basket, Set.of(), 3);

        assertEquals(1, alternatives.size());
        var atacado = alternatives.get(0);
        assertEquals(3, atacado.matchedItemCount());
        // 31.74 + 37.80 + 27.90 — the soap line (23.90) is left out
        assertEquals(new BigDecimal("97.44"), atacado.basketTotal());
        // 6 x 4.79 + 2 x 16.49 + 1 x 25.90
        assertEquals(new BigDecimal("87.62"), atacado.alternativeTotal());
        assertEquals(new BigDecimal("-9.82"), atacado.difference());
        assertEquals(1.2, atacado.distanceKm());
    }

    @Test
    void compare_leavesOutMarketsThatPriceLessThanFortyPercentOfTheBasket() {
        var basket = new ArrayList<BasketLine>();
        for (var index = 0; index < 10; index++) {
            var productId = UUID.randomUUID();
            basket.add(line(productId, "1", "10.00"));
            // ATACADO prices 4 of 10 items (40%); HIPER only 3 (30%).
            if (index < 3) pricedAt(productId, row(ATACADO, "9.00", 1.2), row(HIPER, "8.00", 2.1));
            if (index == 3) pricedAt(productId, row(ATACADO, "9.00", 1.2));
        }

        var alternatives = service.compare(HOME, basket, Set.of(), 3);

        assertEquals(List.of(ATACADO), alternatives.stream().map(alternative -> alternative.cnpjRoot()).toList());
        assertEquals(4, alternatives.get(0).matchedItemCount());
    }

    @Test
    void minMatchedItems_isFortyPercentRoundedUpAndAtLeastOne() {
        assertEquals(1, BasketComparisonService.minMatchedItems(1));
        assertEquals(1, BasketComparisonService.minMatchedItems(2));
        assertEquals(2, BasketComparisonService.minMatchedItems(3));
        assertEquals(2, BasketComparisonService.minMatchedItems(5));
        assertEquals(4, BasketComparisonService.minMatchedItems(10));
        assertEquals(8, BasketComparisonService.minMatchedItems(18));
    }

    @Test
    void compare_readsPricesForTheWholeBasketInOneCall() {
        pricedAt(milk, row(ATACADO, "4.79", 1.2));
        pricedAt(coffee, row(ATACADO, "16.49", 1.2));

        service.compare(HOME, List.of(line(milk, "6", "31.74"), line(coffee, "2", "37.80")), Set.of(), 3);

        verify(priceIndexService, times(1)).nearbyChainPricesForProducts(List.of(milk, coffee), HOME);
    }

    @Test
    void compare_ranksByCoverageThenDistanceAndCapsTheList() {
        var thirdChain = "44444444";
        var fourthChain = "55555555";
        pricedAt(milk, row(ATACADO, "4.79", 3.0), row(HIPER, "5.10", 2.1), row(thirdChain, "5.00", 0.5), row(fourthChain, "5.00", 9.0));
        pricedAt(coffee, row(ATACADO, "16.49", 3.0), row(HIPER, "17.00", 2.1), row(thirdChain, "17.50", 0.5), row(fourthChain, "17.50", 9.0));
        pricedAt(rice, row(ATACADO, "25.90", 3.0), row(HIPER, "26.00", 2.1), row(thirdChain, "26.50", 0.5), row(fourthChain, "26.50", 9.0));
        pricedAt(soap, row(ATACADO, "21.80", 3.0));
        var basket = List.of(line(milk, "6", "31.74"), line(coffee, "2", "37.80"),
                line(rice, "1", "27.90"), line(soap, "1", "23.90"));

        var alternatives = service.compare(HOME, basket, Set.of(), 3);

        // ATACADO covers 4 items; the others cover 3 and come nearest first; the farthest is cut.
        assertEquals(List.of(ATACADO, thirdChain, HIPER),
                alternatives.stream().map(alternative -> alternative.cnpjRoot()).toList());
    }

    @Test
    void compare_excludesTheChainWhereTheBasketWasBought() {
        pricedAt(milk, row(ATACADO, "4.79", 1.2));
        var basket = List.of(line(milk, "6", "31.74"));

        assertTrue(service.compare(HOME, basket, Set.of(ATACADO), 3).isEmpty());
    }

    @Test
    void compare_mergesTheSameProductBoughtOnTwoLines() {
        pricedAt(milk, row(ATACADO, "5.00", 1.2));
        var basket = List.of(line(milk, "2", "10.60"), line(milk, "4", "21.14"));

        var alternatives = service.compare(HOME, basket, Set.of(), 3);

        assertEquals(1, alternatives.get(0).matchedItemCount());
        assertEquals(new BigDecimal("31.74"), alternatives.get(0).basketTotal());
        assertEquals(new BigDecimal("30.00"), alternatives.get(0).alternativeTotal());
    }

    @Test
    void compare_withoutKnownPositionReturnsNothingAndAsksNoPrices() {
        var nowhere = new ViewerLocation(null, null, Source.UF_FALLBACK, "RS");

        assertTrue(service.compare(nowhere, List.of(line(milk, "6", "31.74")), Set.of(), 3).isEmpty());
        verify(priceIndexService, never()).nearbyChainPricesForProducts(any(), any());
    }

    /** Prices are read for the whole basket in one call; each test fills this map first. */
    private final Map<UUID, List<MarketPriceRow>> rowsByProduct = new HashMap<>();

    private void pricedAt(UUID productId, MarketPriceRow... rows) {
        rowsByProduct.put(productId, List.of(rows));
        lenient().when(priceIndexService.nearbyChainPricesForProducts(any(), any())).thenReturn(rowsByProduct);
    }

    private MarketPriceRow row(String cnpjRoot, String latestPrice, double distanceKm) {
        return new MarketPriceRow(cnpjRoot + "000111", cnpjRoot, "Mercado " + cnpjRoot,
                new BigDecimal(latestPrice), new BigDecimal(latestPrice), new BigDecimal(latestPrice),
                LocalDateTime.now().minusDays(2), 5, 3, distanceKm, false, false, false);
    }

    private BasketLine line(UUID productId, String quantity, String lineTotal) {
        return new BasketLine(productId, "Produto", new BigDecimal(quantity), new BigDecimal(lineTotal));
    }
}
