package com.relyon.economizaai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rules of "this purchase at other markets": the comparison of a nota and the single-market
 * alternatives stored with a shopping trip.
 */
@Component
@ConfigurationProperties(prefix = "economizaai.comparison")
public class ComparisonProperties {

    /** Share (%) of the basket's items a market must price to be offered. */
    private int minMatchedPct = 40;
    /** A nota bought more than this many days ago is not compared: prices have moved on. */
    private int receiptMaxAgeDays = 7;
    /** Markets offered per nota and per shopping trip. */
    private int alternativeMarkets = 3;

    /** The share of the basket, rounded up, and never less than one item. */
    public int minMatchedItems(int basketSize) {
        return Math.max(1, Math.ceilDiv(basketSize * minMatchedPct, 100));
    }

    public int getMinMatchedPct() { return minMatchedPct; }
    public void setMinMatchedPct(int minMatchedPct) { this.minMatchedPct = minMatchedPct; }
    public int getReceiptMaxAgeDays() { return receiptMaxAgeDays; }
    public void setReceiptMaxAgeDays(int receiptMaxAgeDays) { this.receiptMaxAgeDays = receiptMaxAgeDays; }
    public int getAlternativeMarkets() { return alternativeMarkets; }
    public void setAlternativeMarkets(int alternativeMarkets) { this.alternativeMarkets = alternativeMarkets; }
}
