package com.relyon.economizaai.dto.response;

/**
 * The user's referral program status (GET /referrals/me). {@code rewardedMonths} is how many
 * months of Premium the user earned by inviting; {@code firstRewardUsed} tells the app the
 * one-time "first friend who scans a receipt" reward is spent (from then on a month is earned
 * per friend who subscribes). {@code canRedeemCode} is true while this account may still enter
 * someone else's code.
 */
public record ReferralStatusResponse(
        String code,
        String shareUrl,
        long invitedCount,
        long qualifiedCount,
        long rewardedMonths,
        boolean firstRewardUsed,
        boolean referredBy,
        boolean canRedeemCode
) {}
