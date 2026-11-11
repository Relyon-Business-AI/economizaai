package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for FREE/PRO gating. Per CLAUDE.md, no controller or
 * service may inline {@code if (user.tier != PRO)} — every paywall decision
 * goes through here.
 *
 * <p>PRO allows everything. For FREE the policy is PER FEATURE: a gated feature is
 * PRO-only unless it is listed in {@code economizaai.subscription.free.open-features}
 * (so paywalls can be switched on one at a time). Two features are METERED rather
 * than on/off — watched markets and monthly receipts — FREE may use them up to a
 * numeric cap ({@link #requireWithinLimit}). Notification DELIVERY has its own rule:
 * transactional notifications are never held back ({@link #allowsDelivery}).
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

    /**
     * Notifications the user must receive whatever their plan: the outcome of something they
     * did (a receipt finished/failed/was recovered), household membership changes, and
     * account/security notices. Only promotional types fall under the PRO delivery gate.
     */
    private static final Set<NotificationType> TRANSACTIONAL_TYPES = EnumSet.of(
            NotificationType.RECEIPT_READY, NotificationType.RECEIPT_FAILED, NotificationType.RECEIPT_RECOVERED,
            NotificationType.HOUSEHOLD_MEMBER_JOINED, NotificationType.HOUSEHOLD_MEMBER_LEFT);

    /**
     * True when the user may use the feature without limit. Enforcement off or PRO → always
     * true; FREE → only the features explicitly left open by config.
     */
    public boolean allows(User user, Feature feature) {
        if (unlimited(user)) return true;
        return free().getOpenFeatures().contains(feature);
    }

    /** Throws {@link PaywallException} (→ 402) when the user can't use the feature. */
    public void require(User user, Feature feature) {
        if (!allows(user, feature)) {
            log.info("paywall.blocked user={} feature={} tier={}",
                    LogMasker.email(user.getEmail()), feature, user.getSubscriptionTier());
            throw new PaywallException(feature.name());
        }
    }

    /**
     * Gate for the METERED features: FREE may use them until {@code currentUsage} reaches the
     * feature's cap; one more is a paywall (→ 402). No-op for PRO / enforcement off / a feature
     * left open to FREE.
     */
    public void requireWithinLimit(User user, Feature feature, long currentUsage) {
        var limit = limitFor(user, feature);
        if (currentUsage >= limit) {
            log.info("paywall.blocked user={} feature={} tier={} usage={} limit={}",
                    LogMasker.email(user.getEmail()), feature, user.getSubscriptionTier(), currentUsage, limit);
            throw new PaywallException(feature.name());
        }
    }

    private int limitFor(User user, Feature feature) {
        return switch (feature) {
            case WATCHED_MARKETS_UNLIMITED -> watchedMarketLimit(user);
            case RECEIPT_UPLOAD_UNLIMITED -> monthlyReceiptLimit(user);
            case HISTORY_FULL_RANGE, PUSH_AND_EMAIL_DELIVERY, BASKET_OPTIMIZATION, CSV_EXPORT, PHOTO_EXTRACTION ->
                    throw new IllegalArgumentException(feature + " is not a metered feature");
        };
    }

    /**
     * May this notification leave the inbox (push / e-mail)? Transactional ones always do —
     * the receipt/household types, and organic SYSTEM notices (account, billing, consent),
     * i.e. SYSTEM not sent by an admin campaign. Everything else is the PRO delivery feature.
     */
    public boolean allowsDelivery(User user, NotificationType type, boolean fromCampaign) {
        if (TRANSACTIONAL_TYPES.contains(type) || (type == NotificationType.SYSTEM && !fromCampaign)) {
            return true;
        }
        return allows(user, Feature.PUSH_AND_EMAIL_DELIVERY);
    }

    /** Max pinned watched markets: FREE = config, PRO/enforcement-off = unlimited. */
    public int watchedMarketLimit(User user) {
        return allows(user, Feature.WATCHED_MARKETS_UNLIMITED) ? Integer.MAX_VALUE : free().getWatchedMarkets();
    }

    /** FREE rolling history window in days; null for PRO/enforcement-off (unlimited). */
    public Integer freeHistoryWindowDays(User user) {
        return allows(user, Feature.HISTORY_FULL_RANGE) ? null : free().getHistoryDays();
    }

    /**
     * Max receipt submissions per calendar month: FREE = config, PRO/enforcement-off = unlimited.
     * Callers count the receipts the user SUBMITTED — household-split copies are not usage.
     */
    public int monthlyReceiptLimit(User user) {
        return allows(user, Feature.RECEIPT_UPLOAD_UNLIMITED) ? Integer.MAX_VALUE : free().getMonthlyReceipts();
    }

    /**
     * How many people may live in a household, given everyone who'd be in it (current members
     * plus the joiner). A household is PRO when ANY of them is PRO — the family-plan spirit:
     * one subscriber lifts the cap for the whole house. With enforcement off, or with a PRO
     * member, the absolute ceiling applies; otherwise the FREE cap. {@code upgradeAvailable}
     * tells the caller whether subscribing would raise the limit (FREE cap hit) or not.
     */
    public MemberCap householdMemberCap(List<User> everyone) {
        var enforced = properties.getSubscription().isEnforce();
        var anyPro = everyone.stream().anyMatch(this::isPro);
        var max = properties.getSubscription().getHouseholdMembersMax();
        if (!enforced || anyPro) {
            return new MemberCap(max, false);
        }
        return new MemberCap(free().getHouseholdMembers(), true);
    }

    public record MemberCap(int limit, boolean upgradeAvailable) {}

    /**
     * Clamp a requested query lower-bound to the user's allowed window. PRO is
     * unaffected (returns the request as-is). FREE is floored at
     * {@code now - historyDays}, so an earlier (or null/unbounded) request can
     * never reach data older than the window.
     */
    public LocalDateTime clampFrom(User user, LocalDateTime requestedFrom) {
        var windowDays = freeHistoryWindowDays(user);
        if (windowDays == null) return requestedFrom;
        var floor = BrazilClock.nowDateTime().minusDays(windowDays);
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
