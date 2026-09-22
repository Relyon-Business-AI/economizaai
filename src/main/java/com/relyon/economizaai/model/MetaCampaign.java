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
import java.time.OffsetDateTime;

/**
 * Current budget/status snapshot for one Meta (Facebook/Instagram) campaign,
 * refreshed by {@code MetaAdSpendSyncJob} next to the daily spend. Upserts on
 * {@code campaignId}. Budgets are already in R$ (converted from Meta's minor
 * units). Powers the "budget remaining / campaign ended" cards on the dashboard.
 */
@Entity
@Table(name = "meta_campaign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MetaCampaign extends BaseEntity {

    @Column(name = "campaign_id", nullable = false, unique = true, length = 60)
    private String campaignId;

    @Column(length = 300)
    private String name;

    @Column(length = 40)
    private String status;

    @Column(name = "lifetime_budget", precision = 12, scale = 2)
    private BigDecimal lifetimeBudget;

    @Column(name = "budget_remaining", precision = 12, scale = 2)
    private BigDecimal budgetRemaining;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;
}
