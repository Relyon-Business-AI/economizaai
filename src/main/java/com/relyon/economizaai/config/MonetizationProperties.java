package com.relyon.economizaai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PRO plan pricing used to MODEL revenue/LTV in the acquisition dashboard while
 * no real payment data exists yet (RevenueCat captures real amounts going
 * forward — see {@code RevenueEvent}). Defaults mirror MONETIZATION.md
 * (R$9,90/mês, R$89/ano). Override via PRO_MONTHLY_PRICE / PRO_YEARLY_PRICE /
 * PRO_ASSUMED_LIFETIME_MONTHS.
 */
@Component
@ConfigurationProperties(prefix = "economizaai.monetization")
public class MonetizationProperties {

    private BigDecimal monthlyPrice = new BigDecimal("9.90");
    private BigDecimal yearlyPrice = new BigDecimal("89.00");
    /** How many months a PRO user is assumed to stay, for the modeled LTV = monthlyPrice × this. */
    private int assumedLifetimeMonths = 12;

    /** Modeled lifetime value of one PRO user = monthlyPrice × assumedLifetimeMonths. */
    public BigDecimal ltvPerProUser() {
        return monthlyPrice.multiply(BigDecimal.valueOf(assumedLifetimeMonths)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public void setMonthlyPrice(BigDecimal monthlyPrice) { this.monthlyPrice = monthlyPrice; }

    public BigDecimal getYearlyPrice() { return yearlyPrice; }
    public void setYearlyPrice(BigDecimal yearlyPrice) { this.yearlyPrice = yearlyPrice; }

    public int getAssumedLifetimeMonths() { return assumedLifetimeMonths; }
    public void setAssumedLifetimeMonths(int assumedLifetimeMonths) { this.assumedLifetimeMonths = assumedLifetimeMonths; }
}
