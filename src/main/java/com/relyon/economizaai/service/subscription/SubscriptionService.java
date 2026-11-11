package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.SubscriptionStatusResponse;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.PremiumGrantRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Owns the subscription lifecycle and keeps {@link User#getSubscriptionTier()}
 * in sync with the {@link Subscription} record. Called by the admin endpoint
 * (manual grants/ops), the launch-promo grant at the first confirmed receipt,
 * the provider webhooks (RevenueCat, Mercado Pago) and the expiry sweep.
 *
 * <p>The tier is PRO while the subscription is ACTIVE or a referral-reward window
 * ({@code PremiumGrant}) is running; whenever a subscription ends the tier is
 * re-resolved instead of being forced to FREE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final CollaborativeProperties collaborativeProperties;
    private final PremiumGrantRepository premiumGrantRepository;
    private final SubscriptionDeletionAlertService subscriptionDeletionAlertService;

    /**
     * Upsert the user's subscription to ACTIVE and set their tier to PRO. Used
     * for real provider activations/renewals and admin grants (provider
     * "manual", open-ended). The launch promo does NOT come through here — see
     * {@link #grantSignupPromoIfEnabled}, which never replaces a better entitlement.
     */
    @Transactional
    public Subscription activatePro(User user, String provider, String providerRef, LocalDateTime periodEnd) {
        var subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().user(user).build());
        var saved = activate(subscription, user, provider, providerRef, periodEnd);
        log.info("subscription.activated user={} provider={} periodEnd={}",
                LogMasker.email(user.getEmail()), provider, periodEnd);
        return saved;
    }

    private Subscription activate(Subscription subscription, User user, String provider, String providerRef,
                                  LocalDateTime periodEnd) {
        subscription.setProvider(provider);
        subscription.setProviderRef(providerRef);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setCancelAtPeriodEnd(false);
        var saved = subscriptionRepository.save(subscription);
        user.setSubscriptionTier(SubscriptionTier.PRO);
        userRepository.save(user);
        return saved;
    }

    /**
     * The provider told us the subscription will not renew (auto-renew turned off, a scheduled
     * pause, a cancelled recurring charge). Access is paid until the period end, so the row stays
     * ACTIVE and the expiry sweep downgrades when the period actually lapses. Ignored when the
     * user's current subscription is not this provider's (e.g. already back on the promo).
     */
    @Transactional
    public void markNonRenewing(User user, String provider) {
        var subscription = activeSubscriptionOf(user, provider);
        if (subscription == null) {
            log.info("subscription.non_renewing.ignored user={} provider={} reason=no_active_subscription",
                    LogMasker.email(user.getEmail()), provider);
            return;
        }
        if (subscription.getCurrentPeriodEnd() == null) {
            // No paid-until date to honour — nothing would ever end this subscription.
            end(subscription, user);
            return;
        }
        subscription.setCancelAtPeriodEnd(true);
        subscriptionRepository.save(subscription);
        log.info("subscription.non_renewing user={} provider={} accessUntil={}",
                LogMasker.email(user.getEmail()), provider, subscription.getCurrentPeriodEnd());
    }

    /**
     * The provider says the paid period is over (expiration webhook). Ends the subscription —
     * falling back to the launch promo when the user still has it. Ignored when the user's
     * current subscription is not this provider's.
     */
    @Transactional
    public void endProviderSubscription(User user, String provider) {
        var subscription = activeSubscriptionOf(user, provider);
        if (subscription == null) {
            log.info("subscription.end.ignored user={} provider={} reason=no_active_subscription",
                    LogMasker.email(user.getEmail()), provider);
            return;
        }
        end(subscription, user);
    }

    private Subscription activeSubscriptionOf(User user, String provider) {
        return subscriptionRepository.findByUserId(user.getId())
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.ACTIVE)
                .filter(subscription -> provider.equals(subscription.getProvider()))
                .orElse(null);
    }

    /**
     * End a lapsed subscription. Used by the expiry sweep; the subscription is
     * passed in with its user already loaded.
     */
    @Transactional
    public void expire(Subscription subscription) {
        end(subscription, subscription.getUser());
    }

    /**
     * A paid subscription that ends while the user's launch promo is still running goes back to
     * the promo (PRO until the promo end). Otherwise the row is EXPIRED and the tier re-resolved:
     * PRO only if a reward window is running, FREE if not.
     */
    private void end(Subscription subscription, User user) {
        if (subscription.isPaid() && launchPromoStillRunning(subscription)) {
            activate(subscription, user, Subscription.MANUAL_PROVIDER, null, subscription.getLaunchPromoUntil());
            log.info("subscription.fell_back_to_launch_promo user={} until={}",
                    LogMasker.email(user.getEmail()), subscription.getLaunchPromoUntil());
            return;
        }
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        subscription.setCancelAtPeriodEnd(false);
        subscriptionRepository.save(subscription);
        var tier = premiumGrantRepository.existsActive(user.getId(), LocalDateTime.now())
                ? SubscriptionTier.PRO
                : SubscriptionTier.FREE;
        user.setSubscriptionTier(tier);
        userRepository.save(user);
        log.info("subscription.expired user={} periodEnd={} tier={}",
                LogMasker.email(user.getEmail()), subscription.getCurrentPeriodEnd(), tier);
    }

    private static boolean launchPromoStillRunning(Subscription subscription) {
        return subscription.getLaunchPromoUntil() != null
                && subscription.getLaunchPromoUntil().isAfter(LocalDateTime.now());
    }

    /**
     * Launch promo: Premium free until the FIXED date {@code economizaai.subscription.promo.until}
     * (end of that day in Brasília; the whole base expires together), falling back to
     * {@code months} from today when no fixed date is configured. Called by
     * {@code ReceiptService} when the user confirms their FIRST receipt — not at registration.
     *
     * <p>Granted at most ONCE per user (remembered on the subscription row), so deleting receipts
     * back to one never re-fires it. It never replaces a better entitlement: an ACTIVE paid
     * subscription, an open-ended admin grant, or a period that already ends later is left
     * untouched (the promo is still remembered, so a paid subscription that ends first falls
     * back to it).
     *
     * @return the granted period end, or {@code null} when nothing changed for the user (promo
     * disabled or over, already granted, or a better entitlement kept) — callers use it to tell
     * the FE whether to show the "you got free Premium" notice.
     */
    @Transactional
    public LocalDateTime grantSignupPromoIfEnabled(User user) {
        var promo = collaborativeProperties.getSubscription().getPromo();
        if (!promo.isEnabled()) {
            return null;
        }
        var periodEnd = resolvePromoPeriodEnd(promo);
        if (periodEnd == null) {
            log.info("subscription.launch_promo_over user={} until={}",
                    LogMasker.email(user.getEmail()), promo.getUntil());
            return null;
        }
        var subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().user(user).build());
        if (subscription.getLaunchPromoGrantedAt() != null) {
            log.debug("subscription.launch_promo_skipped user={} reason=already_granted",
                    LogMasker.email(user.getEmail()));
            return null;
        }
        subscription.setLaunchPromoGrantedAt(LocalDateTime.now());
        subscription.setLaunchPromoUntil(periodEnd);
        if (hasBetterEntitlement(subscription, periodEnd)) {
            subscriptionRepository.save(subscription);
            log.info("subscription.launch_promo_kept_existing user={} provider={} periodEnd={}",
                    LogMasker.email(user.getEmail()), subscription.getProvider(), subscription.getCurrentPeriodEnd());
            return null;
        }
        activate(subscription, user, Subscription.MANUAL_PROVIDER, null, periodEnd);
        log.info("subscription.launch_promo_granted user={} periodEnd={}",
                LogMasker.email(user.getEmail()), periodEnd);
        return periodEnd;
    }

    /** ACTIVE and paid, open-ended (admin grant), or already running past the promo end. */
    private static boolean hasBetterEntitlement(Subscription subscription, LocalDateTime promoPeriodEnd) {
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            return false;
        }
        return subscription.isPaid()
                || subscription.getCurrentPeriodEnd() == null
                || subscription.getCurrentPeriodEnd().isAfter(promoPeriodEnd);
    }

    /** Fixed launch-promo date (end of that Brasília day) when configured and still ahead; months-based fallback otherwise. */
    private LocalDateTime resolvePromoPeriodEnd(CollaborativeProperties.Subscription.Promo promo) {
        if (promo.getUntil() == null) {
            return LocalDateTime.now().plusMonths(promo.getMonths());
        }
        var fixedEnd = EntitlementClock.endOfBrazilDay(promo.getUntil());
        return fixedEnd.isAfter(LocalDateTime.now()) ? fixedEnd : null;
    }

    /** Current tier + provider lifecycle for the self-serve status endpoint. */
    @Transactional(readOnly = true)
    public SubscriptionStatusResponse statusFor(User user) {
        var subscription = subscriptionRepository.findByUserId(user.getId()).orElse(null);
        var latestRewardEnd = premiumGrantRepository.findLatestEndByUserId(user.getId());
        var rewardPremiumUntil = latestRewardEnd != null && latestRewardEnd.isAfter(LocalDateTime.now())
                ? latestRewardEnd
                : null;
        return SubscriptionStatusResponse.from(user, subscription, rewardPremiumUntil);
    }

    /**
     * Admin "set FREE": mark the user's subscription CANCELED and drop their tier to FREE
     * right away. No-op on the subscription row when none exists (tier still forced to FREE).
     * Provider cancellations do NOT come through here — see {@link #markNonRenewing}.
     */
    @Transactional
    public void cancel(User user) {
        subscriptionRepository.findByUserId(user.getId()).ifPresent(subscription -> {
            subscription.setStatus(SubscriptionStatus.CANCELED);
            subscription.setCancelAtPeriodEnd(false);
            subscriptionRepository.save(subscription);
        });
        user.setSubscriptionTier(SubscriptionTier.FREE);
        userRepository.save(user);
        log.info("subscription.canceled user={}", LogMasker.email(user.getEmail()));
    }

    /**
     * Account deletion does not cancel anything at the store / payment provider — the user keeps
     * being charged until they cancel there. Flags it (log + admin e-mail) so support can reach
     * out; never blocks the deletion.
     */
    @Transactional(readOnly = true)
    public void warnIfDeletingPaidSubscriber(User user) {
        subscriptionRepository.findByUserId(user.getId())
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.ACTIVE)
                .filter(Subscription::isPaid)
                .ifPresent(subscription -> {
                    log.warn("account.deleted_with_active_paid_subscription user={} provider={} periodEnd={} nonRenewing={}",
                            LogMasker.email(user.getEmail()), subscription.getProvider(),
                            subscription.getCurrentPeriodEnd(), subscription.isCancelAtPeriodEnd());
                    subscriptionDeletionAlertService.notifyPaidSubscriberDeleted(user, subscription);
                });
    }
}
