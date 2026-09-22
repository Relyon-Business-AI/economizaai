package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.dto.response.SpendInsightsResponse.MarketBucket;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One market in the household's "where I got the most discount" ranking.
 *
 * <p>{@code discount} is the receipt-level discount the household received at
 * this market over the period (in R$), {@code grossTotal} is the gross spend
 * (sum of item prices), and {@code discountRate} is {@code discount / grossTotal}
 * as a 0–1 fraction (FE formats as %). Built from {@link MarketBucket}, so it
 * reuses the same gross spend + discount aggregation.
 */
public record MarketDiscountResponse(
        String cnpj,
        String marketName,
        String marketFriendlyName,
        BigDecimal grossTotal,
        BigDecimal discount,
        BigDecimal discountRate,
        long receiptCount
) {
    public static MarketDiscountResponse from(MarketBucket bucket) {
        var gross = bucket.total() != null ? bucket.total() : BigDecimal.ZERO;
        var discount = bucket.discount() != null ? bucket.discount() : BigDecimal.ZERO;
        var rate = gross.signum() > 0
                ? discount.divide(gross, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new MarketDiscountResponse(bucket.cnpj(), bucket.marketName(), bucket.marketFriendlyName(),
                gross, discount, rate, bucket.receiptCount());
    }
}
