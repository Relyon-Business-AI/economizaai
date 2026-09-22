package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Safety net for lapsed subscriptions: if a renewal webhook never arrives (failed
 * payment, provider hiccup), an ACTIVE subscription whose paid period has passed
 * is downgraded to FREE so a non-paying user doesn't stay PRO forever. Open-ended
 * grants (null period — admin promos) are never touched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionExpiryService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelayString = "${economizaai.subscription.expiry-interval-ms:3600000}",
            initialDelayString = "${economizaai.subscription.expiry-initial-delay-ms:120000}")
    @Transactional
    public void run() {
        var lapsed = subscriptionRepository.findActiveExpiredBefore(SubscriptionStatus.ACTIVE, LocalDateTime.now());
        if (lapsed.isEmpty()) return;
        lapsed.forEach(subscriptionService::expire);
        log.info("subscription.expiry.sweep expired={}", lapsed.size());
    }
}
