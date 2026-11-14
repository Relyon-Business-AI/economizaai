package com.relyon.economizaai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * What the user actually uses of the things the FREE plan caps, next to each FREE cap. The caps
 * are the configured FREE numbers whatever the user's tier, so a PRO user can see what the plan
 * is giving them.
 */
public record SubscriptionUsageResponse(
        @Schema(description = "Receipts the user submitted this calendar month (household-split copies excluded).")
        long receiptsThisMonth,
        @Schema(description = "FREE cap of receipts per calendar month.")
        int freeMonthlyReceipts,
        @Schema(description = "Custom categories the household holds.")
        long customCategories,
        @Schema(description = "FREE cap of custom categories per household.")
        int freeCustomCategories,
        @Schema(description = "Markets the user follows.")
        long watchedMarkets,
        @Schema(description = "FREE cap of followed markets.")
        int freeWatchedMarkets,
        @Schema(description = "Date of the household's oldest confirmed purchase; null without confirmed receipts.")
        LocalDate oldestPurchaseDate,
        @Schema(description = "FREE rolling history window, in days.")
        int freeHistoryDays
) {}
