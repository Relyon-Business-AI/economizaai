package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;

import java.time.LocalDateTime;

/**
 * Self-serve subscription status for the apps. Purchases happen FE-side
 * (App Store / Play via RevenueCat; web provider later) and are reflected
 * here through the billing webhooks — the FE reads this to render
 * "PRO until {currentPeriodEnd}" and route "manage subscription" to the
 * right store. {@code status}/{@code provider}/{@code currentPeriodEnd} are
 * null for users who never had a subscription record (FREE).
 */
public record SubscriptionStatusResponse(
        SubscriptionTier tier,
        SubscriptionStatus status,
        String provider,
        LocalDateTime currentPeriodEnd
) {
    public static SubscriptionStatusResponse from(User user, Subscription subscription) {
        return new SubscriptionStatusResponse(
                user.getSubscriptionTier(),
                subscription == null ? null : subscription.getStatus(),
                subscription == null ? null : subscription.getProvider(),
                subscription == null ? null : subscription.getCurrentPeriodEnd());
    }
}
