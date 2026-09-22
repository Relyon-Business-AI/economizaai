package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    @InjectMocks private SubscriptionExpiryService service;

    private Subscription lapsed() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e").build();
        return Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.now().minusDays(2)).build();
    }

    @Test
    void run_expiresEveryLapsedSubscription() {
        var a = lapsed();
        var b = lapsed();
        when(subscriptionRepository.findActiveExpiredBefore(eq(SubscriptionStatus.ACTIVE), any()))
                .thenReturn(List.of(a, b));

        service.run();

        verify(subscriptionService).expire(a);
        verify(subscriptionService).expire(b);
    }

    @Test
    void run_noLapsed_doesNothing() {
        when(subscriptionRepository.findActiveExpiredBefore(eq(SubscriptionStatus.ACTIVE), any()))
                .thenReturn(List.of());

        service.run();

        verify(subscriptionService, never()).expire(any());
    }
}
