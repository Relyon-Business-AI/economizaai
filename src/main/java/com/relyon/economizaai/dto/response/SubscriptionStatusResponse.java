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
 * {@code cancelAtPeriodEnd} is true when the subscription will not renew (access
 * continues until {@code currentPeriodEnd}); {@code rewardPremiumUntil} is the end
 * of Premium time earned through referrals, when some is still ahead.
 */
public record SubscriptionStatusResponse(
        SubscriptionTier tier,
        SubscriptionStatus status,
        String provider,
        LocalDateTime currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        LocalDateTime rewardPremiumUntil
) {
    /** Back-compat: status without the renewal flag / referral-reward window. */
    public SubscriptionStatusResponse(SubscriptionTier tier, SubscriptionStatus status, String provider,
                                      LocalDateTime currentPeriodEnd) {
        this(tier, status, provider, currentPeriodEnd, false, null);
    }

    public static SubscriptionStatusResponse from(User user, Subscription subscription) {
        return from(user, subscription, null);
    }

    /**
     * @param rewardPremiumUntil end of the user's last referral-reward window when it is still
     *                           ahead (Premium earned by inviting friends), else null
     */
    public static SubscriptionStatusResponse from(User user, Subscription subscription,
                                                  LocalDateTime rewardPremiumUntil) {
        return new SubscriptionStatusResponse(
                user.getSubscriptionTier(),
                subscription == null ? null : subscription.getStatus(),
                subscription == null ? null : subscription.getProvider(),
                subscription == null ? null : subscription.getCurrentPeriodEnd(),
                subscription != null && subscription.isCancelAtPeriodEnd(),
                rewardPremiumUntil);
    }
}
