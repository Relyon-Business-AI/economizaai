package com.relyon.economizaai.service.merchant;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.MerchantSubscriptionRequiredException;
import com.relyon.economizaai.model.MerchantSubscription;
import com.relyon.economizaai.model.enums.MerchantSubscriptionStatus;
import com.relyon.economizaai.repository.MerchantSubscriptionRepository;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantSubscriptionServiceTest {

    private static final String CHAIN_ROOT = "93015006";

    @Mock private MerchantSubscriptionRepository subscriptionRepository;

    private CollaborativeProperties properties;
    private MerchantSubscriptionService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new MerchantSubscriptionService(subscriptionRepository, properties);
    }

    @Test
    void ensureForChain_newChain_getsLaunchPromoFreeUntilConfiguredDate() {
        when(subscriptionRepository.findByCnpjRoot(CHAIN_ROOT)).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var subscription = service.ensureForChain(CHAIN_ROOT);

        assertThat(subscription.getStatus()).isEqualTo(MerchantSubscriptionStatus.PROMO);
        assertThat(subscription.getFreeUntil()).isEqualTo(properties.getMerchant().getFreeUntil());
    }

    @Test
    void ensureForChain_existingChain_isIdempotent() {
        var existing = MerchantSubscription.builder().cnpjRoot(CHAIN_ROOT)
                .status(MerchantSubscriptionStatus.ACTIVE).activatedAt(LocalDateTime.now()).build();
        when(subscriptionRepository.findByCnpjRoot(CHAIN_ROOT)).thenReturn(Optional.of(existing));

        var subscription = service.ensureForChain(CHAIN_ROOT);

        assertThat(subscription).isSameAs(existing);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void isActive_promoWithinWindow_true() {
        var promo = MerchantSubscription.builder().cnpjRoot(CHAIN_ROOT)
                .status(MerchantSubscriptionStatus.PROMO)
                .freeUntil(BrazilClock.today().plusDays(1))
                .activatedAt(LocalDateTime.now()).build();
        when(subscriptionRepository.findByCnpjRoot(CHAIN_ROOT)).thenReturn(Optional.of(promo));

        assertThat(service.isActive(CHAIN_ROOT)).isTrue();
    }

    @Test
    void isActive_promoExpired_false() {
        var promo = MerchantSubscription.builder().cnpjRoot(CHAIN_ROOT)
                .status(MerchantSubscriptionStatus.PROMO)
                .freeUntil(BrazilClock.today().minusDays(1))
                .activatedAt(LocalDateTime.now()).build();
        when(subscriptionRepository.findByCnpjRoot(CHAIN_ROOT)).thenReturn(Optional.of(promo));

        assertThat(service.isActive(CHAIN_ROOT)).isFalse();
    }

    @Test
    void requirePublishing_withoutSubscription_throws402Path() {
        when(subscriptionRepository.findByCnpjRoot(CHAIN_ROOT)).thenReturn(Optional.empty());

        assertThrows(MerchantSubscriptionRequiredException.class, () -> service.requirePublishing(CHAIN_ROOT));
    }
}
