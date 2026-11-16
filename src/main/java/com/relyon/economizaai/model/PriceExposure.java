package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.PriceExposureSurface;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One price the app SHOWED to a person: a product at a market, on which screen, and the plan the
 * person had at that moment. Append-only log, at most one row per (user, product, market,
 * surface, day). Internal only — it feeds the "saw it in the app, then bought cheaper"
 * attribution and is never exposed by an endpoint.
 */
@Entity
@Table(name = "price_exposures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PriceExposureSurface surface;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", nullable = false, length = 20)
    private SubscriptionTier subscriptionTier;

    @Column(name = "shown_on", nullable = false)
    private LocalDate shownOn;

    @Column(name = "shown_at", nullable = false)
    private OffsetDateTime shownAt;

    /** When a later purchase was attributed to this exposure; null until then, set at most once. */
    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;
}
