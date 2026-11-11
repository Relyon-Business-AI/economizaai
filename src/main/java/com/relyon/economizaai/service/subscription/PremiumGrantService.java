package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.PremiumGrant;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PremiumGrantKind;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Premium time earned outside billing (referral rewards). A reward is one calendar month that
 * queues AFTER whatever the user already has — the running subscription period (promo or paid)
 * and any earlier reward — so months accumulate and a reward never replaces or shortens a
 * subscription. The tier is PRO while a subscription is ACTIVE or a grant window is running;
 * {@link #reconcileTiers} flips the tier when a window starts or ends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumGrantService {

    private final PremiumGrantRepository premiumGrantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    /**
     * Grants one month to {@code user} because of {@code sourceUserId} (the invited account).
     * Idempotent: an invited person yields at most one reward. Empty when it was already granted.
     */
    @Transactional
    public Optional<PremiumGrant> grantMonth(User user, PremiumGrantKind kind, UUID sourceUserId) {
        if (premiumGrantRepository.existsByUserIdAndSourceUserId(user.getId(), sourceUserId)) {
            log.info("premium_grant.skipped reason=already_rewarded user={} kind={}",
                    LogMasker.email(user.getEmail()), kind);
            return Optional.empty();
        }
        var now = LocalDateTime.now();
        var window = EntitlementClock.monthAfter(now, currentEntitlementEnd(user.getId(), now));
        var grant = premiumGrantRepository.save(PremiumGrant.builder()
                .user(user)
                .kind(kind)
                .sourceUserId(sourceUserId)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .build());
        if (!window.startsAt().isAfter(now)) {
            promote(user);
        }
        log.info("premium_grant.created user={} kind={} startsAt={} endsAt={}",
                LogMasker.email(user.getEmail()), kind, window.startsAt(), window.endsAt());
        return Optional.of(grant);
    }

    /**
     * Where the user's Premium currently ends: the later of the ACTIVE subscription's period end
     * and the last grant window. Null when neither reaches past {@code now}. An open-ended
     * subscription (admin grant) has no end to queue after, so only grants count for it.
     */
    private LocalDateTime currentEntitlementEnd(UUID userId, LocalDateTime now) {
        var subscriptionEnd = subscriptionRepository.findByUserId(userId)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.ACTIVE)
                .map(subscription -> subscription.getCurrentPeriodEnd())
                .orElse(null);
        var latestGrantEnd = premiumGrantRepository.findLatestEndByUserId(userId);
        var latest = laterOf(subscriptionEnd, latestGrantEnd);
        return latest != null && latest.isAfter(now) ? latest : null;
    }

    private static LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    public boolean hasActiveGrant(UUID userId) {
        return premiumGrantRepository.existsActive(userId, LocalDateTime.now());
    }

    /**
     * Brings the cached tier in line with the grant windows: promotes FREE users whose window
     * started and downgrades PRO users whose windows all ended with no ACTIVE subscription.
     */
    @Transactional
    public void reconcileTiers() {
        var now = LocalDateTime.now();
        var started = premiumGrantRepository.findFreeUsersWithActiveGrant(now);
        started.forEach(this::promote);
        var ended = premiumGrantRepository.findProUsersWithOnlyEndedGrants(now);
        ended.forEach(this::downgrade);
        if (!started.isEmpty() || !ended.isEmpty()) {
            log.info("premium_grant.reconcile promoted={} downgraded={}", started.size(), ended.size());
        }
    }

    private void promote(User user) {
        if (user.getSubscriptionTier() == SubscriptionTier.PRO) return;
        user.setSubscriptionTier(SubscriptionTier.PRO);
        userRepository.save(user);
    }

    private void downgrade(User user) {
        user.setSubscriptionTier(SubscriptionTier.FREE);
        userRepository.save(user);
    }
}
