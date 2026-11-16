package com.relyon.economizaai.service.exposure;

import com.relyon.economizaai.dto.response.DealResponse;
import com.relyon.economizaai.dto.response.MarketProductPriceResponse;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse;
import com.relyon.economizaai.service.priceindex.CommunityPromoService.CommunityPromo;
import com.relyon.economizaai.service.priceindex.PriceIndexService.MarketPriceRow;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One price a screen put in front of a person: a product, the market and the unit price shown. */
public record ShownPrice(UUID productId, String marketCnpj, BigDecimal unitPrice) {

    public static List<ShownPrice> ofDeals(List<DealResponse> deals) {
        return deals.stream()
                .map(deal -> new ShownPrice(deal.productId(), deal.marketCnpj(), deal.currentPrice()))
                .toList();
    }

    public static List<ShownPrice> ofBestMarkets(UUID productId, List<MarketPriceRow> marketRows) {
        return marketRows.stream()
                .map(marketRow -> new ShownPrice(productId, marketRow.cnpj(), marketRow.latestPrice()))
                .toList();
    }

    public static List<ShownPrice> ofCommunityPromos(List<CommunityPromo> promos) {
        return promos.stream()
                .map(promo -> new ShownPrice(promo.productId(), promo.marketCnpj(), promo.currentMedianPrice()))
                .toList();
    }

    public static List<ShownPrice> ofShoppingPlan(ShoppingPlanResponse plan) {
        return plan.marketPlans().stream()
                .flatMap(marketPlan -> marketPlan.items().stream()
                        .map(planItem -> new ShownPrice(planItem.productId(), marketPlan.marketCnpj(),
                                planItem.estimatedUnitPrice())))
                .toList();
    }

    public static List<ShownPrice> ofMarketPrices(String marketCnpj, List<MarketProductPriceResponse> productPrices) {
        return productPrices.stream()
                .map(productPrice -> new ShownPrice(productPrice.productId(), marketCnpj, productPrice.latestPrice()))
                .toList();
    }

    boolean isComplete() {
        return productId != null && marketCnpj != null && !marketCnpj.isBlank()
                && unitPrice != null && unitPrice.signum() > 0;
    }
}
