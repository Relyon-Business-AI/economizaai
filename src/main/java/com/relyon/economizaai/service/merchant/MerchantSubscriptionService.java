package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.MerchantSubscriptionRequiredException;
import com.relyon.economizaai.model.MerchantSubscription;
import com.relyon.economizaai.model.enums.MerchantSubscriptionStatus;
import com.relyon.economizaai.repository.MerchantSubscriptionRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The single gate for merchant marketing-account entitlements (CLAUDE.md: no
 * inline tier checks). Launch promo: every chain that claims gets PROMO status,
 * free until economizaai.merchant.free-until (2026-12-31). Payment integration
 * is still INERT — after the promo window, ACTIVE must be set manually until a
 * provider is wired (DEV_NOTES.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantSubscriptionService {

    private final MerchantSubscriptionRepository subscriptionRepository;
    private final CollaborativeProperties properties;

    /** Idempotent — called on claim approval. Every new chain gets the launch promo. */
    @Transactional
    public MerchantSubscription ensureForChain(String cnpjRoot) {
        return subscriptionRepository.findByCnpjRoot(cnpjRoot).orElseGet(() -> {
            var subscription = subscriptionRepository.save(MerchantSubscription.builder()
                    .cnpjRoot(cnpjRoot)
                    .status(MerchantSubscriptionStatus.PROMO)
                    .freeUntil(properties.getMerchant().getFreeUntil())
                    .activatedAt(BrazilClock.nowDateTime())
                    .build());
            log.info("merchant.subscription.promo_granted cnpjRoot={} freeUntil={}",
                    cnpjRoot, subscription.getFreeUntil());
            return subscription;
        });
    }

    @Transactional(readOnly = true)
    public boolean isActive(String cnpjRoot) {
        return subscriptionRepository.findByCnpjRoot(cnpjRoot)
                .map(subscription -> subscription.isActiveOn(BrazilClock.today()))
                .orElse(false);
    }

    /** Publishing gate — promo CRUD/import call this before any write. */
    @Transactional(readOnly = true)
    public void requirePublishing(String cnpjRoot) {
        if (!isActive(cnpjRoot)) {
            throw new MerchantSubscriptionRequiredException();
        }
    }

    @Transactional(readOnly = true)
    public List<MerchantSubscription> forChains(List<String> cnpjRoots) {
        return cnpjRoots.isEmpty() ? List.of() : subscriptionRepository.findAllByCnpjRootIn(cnpjRoots);
    }
}
