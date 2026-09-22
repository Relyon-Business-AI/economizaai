package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One day of Meta (Facebook/Instagram) ad spend for one campaign, pulled from
 * the Marketing API insights endpoint by {@code MetaAdSpendSyncJob}. Re-syncs
 * upsert on the (campaignId, spendDate) unique key so a day can be refreshed as
 * Meta finalizes its numbers. Only ever written when the Meta integration is
 * configured; the acquisition dashboard reads it to compute cost-per-signup.
 */
@Entity
@Table(name = "meta_ad_spend")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MetaAdSpend extends BaseEntity {

    @Column(name = "ad_account_id", nullable = false, length = 60)
    private String adAccountId;

    @Column(name = "campaign_id", nullable = false, length = 60)
    private String campaignId;

    @Column(name = "campaign_name", length = 300)
    private String campaignName;

    @Column(name = "spend_date", nullable = false)
    private LocalDate spendDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal spend;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long impressions;

    @Column(nullable = false)
    private long clicks;

    @Column(nullable = false)
    private long reach;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;
}
