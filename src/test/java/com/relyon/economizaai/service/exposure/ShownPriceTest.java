package com.relyon.economizaai.service.exposure;

import com.relyon.economizaai.dto.response.MarketProductPriceResponse;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.MarketPlan;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.priceindex.CommunityPromoService.CommunityPromo;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShownPriceTest {

    private static final String MARKET_CNPJ = "12345678000199";
    private static final String OTHER_MARKET_CNPJ = "98765432000155";

    @Test
    void bestMarketsShowTheLatestPriceOfEachMarketForTheProduct() {
        var productId = UUID.randomUUID();
        var marketRow = new MarketPriceRow(MARKET_CNPJ, "12345678", "Mercado X",
                new BigDecimal("8.00"), new BigDecimal("7.00"), new BigDecimal("7.50"),
                LocalDateTime.now(), 4, 2, null, false, true, false);

        assertEquals(List.of(new ShownPrice(productId, MARKET_CNPJ, new BigDecimal("7.50"))),
                ShownPrice.ofBestMarkets(productId, List.of(marketRow)));
    }

    @Test
    void communityPromosShowTheCurrentPriceAtThePromoMarket() {
        var productId = UUID.randomUUID();
        var promo = new CommunityPromo(productId, "Leite", MARKET_CNPJ, "12345678", "Mercado X", null,
                new BigDecimal("4.99"), new BigDecimal("6.49"), new BigDecimal("23.1"), 5, 3, null, false, "RS");

        assertEquals(List.of(new ShownPrice(productId, MARKET_CNPJ, new BigDecimal("4.99"))),
                ShownPrice.ofCommunityPromos(List.of(promo)));
    }

    @Test
    void shoppingPlanShowsEachItemAtTheMarketItWasAssignedTo() {
        var firstProduct = UUID.randomUUID();
        var secondProduct = UUID.randomUUID();
        var plan = new ShoppingPlanResponse(List.of(
                new MarketPlan(MARKET_CNPJ, "Mercado X", null, new BigDecimal("10.00"), 1, List.of(
                        new PlanItem(firstProduct, "Arroz", null, BigDecimal.ONE, new BigDecimal("10.00"),
                                new BigDecimal("10.00"), PlanItem.PriceSource.COMMUNITY_INDEX))),
                new MarketPlan(OTHER_MARKET_CNPJ, "Mercado Y", null, new BigDecimal("6.00"), 1, List.of(
                        new PlanItem(secondProduct, "Feijão", null, BigDecimal.ONE, new BigDecimal("6.00"),
                                new BigDecimal("6.00"), PlanItem.PriceSource.LOCAL_HISTORY)))),
                new BigDecimal("16.00"), List.of());

        assertEquals(List.of(
                        new ShownPrice(firstProduct, MARKET_CNPJ, new BigDecimal("10.00")),
                        new ShownPrice(secondProduct, OTHER_MARKET_CNPJ, new BigDecimal("6.00"))),
                ShownPrice.ofShoppingPlan(plan));
    }

    @Test
    void marketPricesShowEachProductAtTheMarketOfTheScreen() {
        var productId = UUID.randomUUID();
        var productPrice = new MarketProductPriceResponse(productId, "Café", ProductCategory.values()[0],
                new BigDecimal("17.90"), LocalDate.now());

        assertEquals(List.of(new ShownPrice(productId, MARKET_CNPJ, new BigDecimal("17.90"))),
                ShownPrice.ofMarketPrices(MARKET_CNPJ, List.of(productPrice)));
    }
}
