package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.SubscriptionStatusResponse;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
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
 * (manual promos/ops) and the provider-agnostic billing webhook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final CollaborativeProperties collaborativeProperties;

    /**
     * Upsert the user's subscription to ACTIVE and set their tier to PRO. Used
     * for both real provider activations and manual grants (provider/providerRef
     * may be null/"manual").
     */
    @Transactional
    public Subscription activatePro(User user, String provider, String providerRef, LocalDateTime periodEnd) {
        var subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().user(user).build());
        subscription.setProvider(provider);
        subscription.setProviderRef(providerRef);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEnd(periodEnd);
        var saved = subscriptionRepository.save(subscription);

        user.setSubscriptionTier(SubscriptionTier.PRO);
        userRepository.save(user);
        log.info("subscription.activated user={} provider={} periodEnd={}",
                LogMasker.email(user.getEmail()), provider, periodEnd);
        return saved;
    }

    /**
     * Mark a lapsed subscription EXPIRED and drop its user to FREE. Used by the
     * expiry sweep and by provider "expiration" webhooks. The subscription is
     * passed in with its user already loaded.
     */
    @Transactional
    public void expire(Subscription subscription) {
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(subscription);
        var user = subscription.getUser();
        user.setSubscriptionTier(SubscriptionTier.FREE);
        userRepository.save(user);
        log.info("subscription.expired user={} periodEnd={}",
                LogMasker.email(user.getEmail()), subscription.getCurrentPeriodEnd());
    }

    /**
     * Signup promo hook (launch promo): grants a freshly-registered user PRO
     * until the FIXED {@code economizaai.subscription.promo.until} date (whole
     * base expires together), falling back to {@code months} from today when no
     * fixed date is configured. Called right after user creation by both the
     * email/password and social registration flows.
     *
     * @return the granted period end, or {@code null} when the promo is
     * disabled or the fixed date has already passed (no grant happened) —
     * callers use this to tell the FE whether to show the signup-promo banner.
     */
    @Transactional
    public LocalDateTime grantSignupPromoIfEnabled(User user) {
        var promo = collaborativeProperties.getSubscription().getPromo();
        if (!promo.isEnabled()) {
            return null;
        }
        var periodEnd = resolvePromoPeriodEnd(promo);
        if (periodEnd == null) {
            log.info("subscription.signup_promo_over user={} until={}",
                    LogMasker.email(user.getEmail()), promo.getUntil());
            return null;
        }
        activatePro(user, "manual", null, periodEnd);
        log.info("subscription.signup_promo_granted user={} periodEnd={}",
                LogMasker.email(user.getEmail()), periodEnd);
        return periodEnd;
    }

    /** Fixed launch-promo date (end of day) when configured and still ahead; months-based fallback otherwise. */
    private LocalDateTime resolvePromoPeriodEnd(CollaborativeProperties.Subscription.Promo promo) {
        if (promo.getUntil() == null) {
            return LocalDateTime.now().plusMonths(promo.getMonths());
        }
        var fixedEnd = promo.getUntil().atTime(23, 59, 59);
        return fixedEnd.isAfter(LocalDateTime.now()) ? fixedEnd : null;
    }

    /** Current tier + provider lifecycle for the self-serve status endpoint. */
    @Transactional(readOnly = true)
    public SubscriptionStatusResponse statusFor(User user) {
        var subscription = subscriptionRepository.findByUserId(user.getId()).orElse(null);
        return SubscriptionStatusResponse.from(user, subscription);
    }

    /**
     * Mark the user's subscription CANCELED and drop their tier to FREE. No-op
     * on the subscription row when none exists (tier still forced to FREE).
     */
    @Transactional
    public void cancel(User user) {
        subscriptionRepository.findByUserId(user.getId()).ifPresent(subscription -> {
            subscription.setStatus(SubscriptionStatus.CANCELED);
            subscriptionRepository.save(subscription);
        });
        user.setSubscriptionTier(SubscriptionTier.FREE);
        userRepository.save(user);
        log.info("subscription.canceled user={}", LogMasker.email(user.getEmail()));
    }
}
