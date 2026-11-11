package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionExpiryServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private PremiumGrantService premiumGrantService;

    private SubscriptionExpiryService service;

    @BeforeEach
    void setUp() {
        // default grace: 3 days
        service = new SubscriptionExpiryService(subscriptionRepository, subscriptionService,
                premiumGrantService, new CollaborativeProperties());
    }

    private Subscription lapsed(String provider, int daysAgo) {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").build();
        return Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE).provider(provider)
                .currentPeriodEnd(LocalDateTime.now().minusDays(daysAgo)).build();
    }

    private void lapsedAre(Subscription... subscriptions) {
        when(subscriptionRepository.findActiveExpiredBefore(eq(SubscriptionStatus.ACTIVE), any()))
                .thenReturn(List.of(subscriptions));
    }

    @Test
    void run_expiresEveryLapsedPromoSubscriptionWithoutGrace() {
        var promo = lapsed("manual", 0);
        var legacyNullProvider = lapsed(null, 1);
        lapsedAre(promo, legacyNullProvider);

        service.run();

        verify(subscriptionService).expire(promo);
        verify(subscriptionService).expire(legacyNullProvider);
    }

    @Test
    void run_givesAPaidRenewingSubscriptionAGraceWindow() {
        var lateRenewal = lapsed("revenuecat", 2);
        lapsedAre(lateRenewal);

        service.run();

        verify(subscriptionService, never()).expire(any());
    }

    @Test
    void run_expiresAPaidSubscriptionOnceTheGraceIsOver() {
        var unpaid = lapsed("mercadopago", 4);
        lapsedAre(unpaid);

        service.run();

        verify(subscriptionService).expire(unpaid);
    }

    @Test
    void run_expiresANonRenewingPaidSubscriptionAtPeriodEnd() {
        var cancelled = lapsed("revenuecat", 0);
        cancelled.setCancelAtPeriodEnd(true);
        lapsedAre(cancelled);

        service.run();

        verify(subscriptionService).expire(cancelled);
    }

    @Test
    void run_noLapsed_doesNothingButStillReconcilesRewardWindows() {
        lapsedAre();

        service.run();

        verify(subscriptionService, never()).expire(any());
        verify(premiumGrantService).reconcileTiers();
    }
}
