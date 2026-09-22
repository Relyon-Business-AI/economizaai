package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The full LGPD personal-data export for one authenticated user: their account,
 * household (with members and household-scoped customizations), and every table
 * that holds data attributable to them. Built entirely inside the read-only
 * export transaction so lazy associations are resolved before the entity
 * manager closes.
 */
public record UserDataExportResponse(
        UserResponse user,
        AccountExtras accountExtras,
        HouseholdResponse household,
        List<ReceiptResponse> receipts,
        List<NotificationRuleResponse> notificationRules,
        List<NotificationPreferenceSummary> notificationPreferences,
        List<String> watchedMarketCnpjs,
        SubscriptionSummary subscription,
        List<MarketAlias> marketAliases,
        List<CustomCategory> customCategories,
        List<CategoryOverride> categoryOverrides,
        List<ProductAliasSummary> productAliases,
        List<BrandPreferenceSummary> brandPreferences,
        List<ManualPurchaseSummary> manualPurchases,
        List<ConsumptionSnoozeSummary> consumptionSnoozes,
        List<ShoppingListSummary> shoppingLists,
        List<RecentViewSummary> recentlyViewedProducts,
        List<NotificationResponse> notifications,
        List<NotificationEventSummary> notificationEvents,
        List<DealSurfaceStateSummary> dealSurfaceStates,
        List<DataShareConsentSummary> dataShareConsents,
        LocalDateTime exportedAt
) {

    /** Account fields not surfaced by {@link UserResponse}. */
    public record AccountExtras(
            String pushDeviceToken,
            LocalDateTime pushTokenUpdatedAt,
            boolean emailVerified,
            LocalDateTime emailVerifiedAt,
            boolean contributionOptIn,
            String phoneNumber,
            boolean phoneVerified,
            String profilePictureKey,
            String profilePictureContentType,
            LocalDateTime profilePictureUploadedAt,
            String digestFrequency,
            Integer digestSendHour,
            LocalDateTime homeSetAt,
            String authProvider,
            String providerSubject,
            String acceptedTermsVersion,
            String acceptedPrivacyVersion,
            LocalDateTime acceptedLegalAt
    ) {}

    public record SubscriptionSummary(
            String provider,
            SubscriptionStatus status,
            LocalDateTime currentPeriodEnd
    ) {}

    public record MarketAlias(String marketCnpj, String customName) {}

    public record CustomCategory(UUID id, String name) {}

    public record CategoryOverride(UUID productId, String effectiveLabel) {}

    public record ProductAliasSummary(UUID productId, String friendlyName) {}

    public record BrandPreferenceSummary(String genericName, String brand, String strength) {}

    public record ManualPurchaseSummary(
            UUID productId,
            BigDecimal quantity,
            LocalDateTime purchasedAt
    ) {}

    public record ConsumptionSnoozeSummary(UUID productId, LocalDateTime snoozedUntil) {}

    public record ShoppingListSummary(UUID id, String name, List<ShoppingListItemSummary> items) {}

    public record ShoppingListItemSummary(UUID productId, String freeText, BigDecimal quantity, boolean checked) {}

    public record RecentViewSummary(UUID productId, LocalDateTime viewedAt) {}

    public record NotificationPreferenceSummary(String type, String channel) {}

    public record NotificationEventSummary(
            String eventType,
            UUID productId,
            String marketCnpj,
            String channel,
            BigDecimal savingsAmount,
            OffsetDateTime occurredAt
    ) {}

    public record DealSurfaceStateSummary(UUID productId, String marketCnpj, OffsetDateTime lastSurfacedAt) {}

    public record DataShareConsentSummary(String status, String scope, LocalDateTime resolvedAt) {}
}
