package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Single source of truth for FREE/PRO gating. Per CLAUDE.md, no controller or
 * service may inline {@code if (user.tier != PRO)} — every paywall decision
 * goes through here.
 *
 * <p>PRO allows everything. FREE is allowed only the features that aren't gated;
 * the typed limit helpers expose the numeric caps (watched markets, history
 * window, monthly receipts) so callers enforce them consistently.
 *
 * <p><strong>Enforcement is OFF by default</strong>
 * ({@code economizaai.subscription.enforce=false}). While off, every call here
 * behaves as if the user were PRO — nothing is blocked, no cap applies — so the
 * paywall is fully dormant but the wiring stays in place. Flip the flag (env
 * {@code SUBSCRIPTION_ENFORCE=true}) to turn the FREE caps on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionGateService {

    private final CollaborativeProperties properties;

    /** True when the user's tier grants the feature. Enforcement off or PRO → always true. */
    public boolean allows(User user, Feature feature) {
        if (unlimited(user)) return true;
        // FREE users are blocked from every PRO-gated feature. The numeric
        // caps (watched markets, receipts, history) are enforced by the
        // dedicated limit helpers below, not by allows().
        return false;
    }

    /** Throws {@link PaywallException} (→ 402) when the user can't use the feature. */
    public void require(User user, Feature feature) {
        if (!allows(user, feature)) {
            log.info("paywall.blocked user={} feature={} tier={}",
                    user.getEmail(), feature, user.getSubscriptionTier());
            throw new PaywallException(feature.name());
        }
    }

    /** Max pinned watched markets: FREE = config, PRO/enforcement-off = unlimited. */
    public int watchedMarketLimit(User user) {
        return unlimited(user) ? Integer.MAX_VALUE : free().getWatchedMarkets();
    }

    /** FREE rolling history window in days; null for PRO/enforcement-off (unlimited). */
    public Integer freeHistoryWindowDays(User user) {
        return unlimited(user) ? null : free().getHistoryDays();
    }

    /** Max receipt submissions per calendar month: FREE = config, PRO/enforcement-off = unlimited. */
    public int monthlyReceiptLimit(User user) {
        return unlimited(user) ? Integer.MAX_VALUE : free().getMonthlyReceipts();
    }

    /**
     * Clamp a requested query lower-bound to the user's allowed window. PRO is
     * unaffected (returns the request as-is). FREE is floored at
     * {@code now - historyDays}, so an earlier (or null/unbounded) request can
     * never reach data older than the window.
     */
    public LocalDateTime clampFrom(User user, LocalDateTime requestedFrom) {
        var windowDays = freeHistoryWindowDays(user);
        if (windowDays == null) return requestedFrom;
        var floor = LocalDateTime.now().minusDays(windowDays);
        if (requestedFrom == null || requestedFrom.isBefore(floor)) return floor;
        return requestedFrom;
    }

    /** No cap applies when enforcement is off (paywall dormant) or the user is PRO. */
    private boolean unlimited(User user) {
        return !properties.getSubscription().isEnforce() || isPro(user);
    }

    private boolean isPro(User user) {
        return user.getSubscriptionTier() == SubscriptionTier.PRO;
    }

    private CollaborativeProperties.Subscription.Free free() {
        return properties.getSubscription().getFree();
    }
}
