package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Ends lapsed entitlements: an ACTIVE subscription whose period has passed is downgraded so a
 * non-paying user doesn't stay PRO forever. Open-ended grants (null period — admin grants) are
 * never touched.
 *
 * <p>A PAID subscription that is still renewing gets a grace window
 * ({@code economizaai.subscription.paid-grace-days}) past its period end — the renewal webhook
 * may simply be late, and downgrading a paying customer is the worse mistake. The launch promo
 * and subscriptions already marked non-renewing end on time. The same pass keeps the tier in
 * line with referral-reward windows starting or ending.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionExpiryService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final PremiumGrantService premiumGrantService;
    private final CollaborativeProperties properties;

    @Scheduled(fixedDelayString = "${economizaai.subscription.expiry-interval-ms:3600000}",
            initialDelayString = "${economizaai.subscription.expiry-initial-delay-ms:120000}")
    @Transactional
    public void run() {
        expireLapsedSubscriptions();
        premiumGrantService.reconcileTiers();
    }

    private void expireLapsedSubscriptions() {
        var now = LocalDateTime.now();
        var graceCutoff = now.minusDays(properties.getSubscription().getPaidGraceDays());
        var lapsed = subscriptionRepository.findActiveExpiredBefore(SubscriptionStatus.ACTIVE, now);
        var due = lapsed.stream()
                .filter(subscription -> !isWithinPaidGrace(subscription, graceCutoff))
                .toList();
        if (lapsed.isEmpty()) return;
        due.forEach(subscriptionService::expire);
        log.info("subscription.expiry.sweep expired={} inGrace={}", due.size(), lapsed.size() - due.size());
    }

    private static boolean isWithinPaidGrace(Subscription subscription, LocalDateTime graceCutoff) {
        return subscription.isPaid()
                && !subscription.isCancelAtPeriodEnd()
                && !subscription.getCurrentPeriodEnd().isBefore(graceCutoff);
    }
}
