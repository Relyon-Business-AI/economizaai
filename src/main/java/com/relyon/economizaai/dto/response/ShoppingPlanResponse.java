package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Output of POST /shopping-lists/optimize. Groups the requested items per market to minimize
 * total cost within the route rules of the request (max distance, max stops, minimum saving
 * per extra stop). Items the system has no usable price for land in {@link #unpriced}.
 *
 * <p>{@code marketPlans} is the ROUTE ORDER: nearest store first when the shopper's position
 * is known, otherwise largest subtotal first. {@code skippedSavings} lists the items that are
 * cheaper at a store the route rules left out, so the app can explain the trade-off.
 */
public record ShoppingPlanResponse(
        List<MarketPlan> marketPlans,
        BigDecimal estimatedTotal,
        List<UnpricedItem> unpriced,
        List<SkippedSaving> skippedSavings
) {
    /** A plan with no saving left out by the route rules. */
    public ShoppingPlanResponse(List<MarketPlan> marketPlans, BigDecimal estimatedTotal,
                                List<UnpricedItem> unpriced) {
        this(marketPlans, estimatedTotal, unpriced, List.of());
    }

    /**
     * One stop of the route. {@code latitude}/{@code longitude} are null until the store is
     * geocoded; {@code distanceKm} is null when the store or the shopper has no known position.
     */
    public record MarketPlan(
            String marketCnpj,
            String marketName,
            String marketFriendlyName,
            BigDecimal subtotal,
            int itemCount,
            List<PlanItem> items,
            BigDecimal latitude,
            BigDecimal longitude,
            Double distanceKm
    ) {
        /** A stop with no known position. */
        public MarketPlan(String marketCnpj, String marketName, String marketFriendlyName,
                          BigDecimal subtotal, int itemCount, List<PlanItem> items) {
            this(marketCnpj, marketName, marketFriendlyName, subtotal, itemCount, items, null, null, null);
        }

        /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
        public MarketPlan withMarketFriendlyName(String marketFriendlyName) {
            return new MarketPlan(marketCnpj, marketName, marketFriendlyName, subtotal, itemCount, items,
                    latitude, longitude, distanceKm);
        }
    }

    public record PlanItem(
            UUID productId,
            String productName,
            String friendlyDescription,
            BigDecimal quantity,
            BigDecimal estimatedUnitPrice,
            BigDecimal estimatedSubtotal,
            PriceSource priceSource
    ) {
        public enum PriceSource { LOCAL_HISTORY, COMMUNITY_INDEX }
    }

    public record UnpricedItem(
            UUID productId,
            String productName,
            String friendlyDescription,
            BigDecimal quantity,
            String reason
    ) {}

    /**
     * An item that would be cheaper at a store the route rules left out. {@code saving} is the
     * total for the requested quantity (R$), always positive.
     */
    public record SkippedSaving(
            UUID productId,
            String productName,
            String friendlyDescription,
            String marketCnpj,
            String marketName,
            String marketFriendlyName,
            BigDecimal saving
    ) {
        /** Copy with the household's custom display name applied. */
        public SkippedSaving withMarketFriendlyName(String marketFriendlyName) {
            return new SkippedSaving(productId, productName, friendlyDescription, marketCnpj, marketName,
                    marketFriendlyName, saving);
        }
    }
}
