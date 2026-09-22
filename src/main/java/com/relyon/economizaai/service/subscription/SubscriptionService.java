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
     * Signup promo hook ("até segunda ordem"): grants a freshly-registered user
     * PRO for {@code economizaai.subscription.promo.months} from today, when the
     * promo is enabled. Called right after user creation by both the
     * email/password and social registration flows.
     *
     * @return the granted period end, or {@code null} when the promo is
     * disabled (no grant happened) — callers use this to tell the FE whether
     * to show the signup-promo banner.
     */
    @Transactional
    public LocalDateTime grantSignupPromoIfEnabled(User user) {
        var promo = collaborativeProperties.getSubscription().getPromo();
        if (!promo.isEnabled()) {
            return null;
        }
        var periodEnd = LocalDateTime.now().plusMonths(promo.getMonths());
        activatePro(user, "manual", null, periodEnd);
        log.info("subscription.signup_promo_granted user={} months={}",
                LogMasker.email(user.getEmail()), promo.getMonths());
        return periodEnd;
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
