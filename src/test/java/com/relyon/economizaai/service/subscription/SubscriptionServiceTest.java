package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private CollaborativeProperties collaborativeProperties;

    @InjectMocks private SubscriptionService service;

    private User freeUser() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.FREE).build();
    }

    @Test
    void activatePro_insertsSubscriptionAndSetsTierWhenNoneExists() {
        var user = freeUser();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
        var periodEnd = LocalDateTime.now().plusDays(30);

        service.activatePro(user, "stripe", "sub_123", periodEnd);

        var captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(SubscriptionStatus.ACTIVE, saved.getStatus());
        assertEquals("stripe", saved.getProvider());
        assertEquals("sub_123", saved.getProviderRef());
        assertEquals(periodEnd, saved.getCurrentPeriodEnd());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
        verify(userRepository).save(user);
    }

    @Test
    void activatePro_upsertsExistingSubscription() {
        var user = freeUser();
        var existing = Subscription.builder().user(user)
                .status(SubscriptionStatus.CANCELED).provider("old").build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        service.activatePro(user, "mercadopago", "mp_9", null);

        assertEquals(SubscriptionStatus.ACTIVE, existing.getStatus());
        assertEquals("mercadopago", existing.getProvider());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
    }

    @Test
    void cancel_marksSubscriptionCanceledAndDropsTier() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO).build();
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        service.cancel(user);

        assertEquals(SubscriptionStatus.CANCELED, existing.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(subscriptionRepository).save(existing);
        verify(userRepository).save(user);
    }

    @Test
    void expire_marksSubscriptionExpiredAndDropsTier() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO).build();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.now().minusDays(1)).build();

        service.expire(subscription);

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(subscriptionRepository).save(subscription);
        verify(userRepository).save(user);
    }

    @Test
    void cancel_dropsTierEvenWhenNoSubscriptionRow() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        service.cancel(user);

        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(userRepository).save(user);
    }

    @Test
    void statusFor_reflectsSubscriptionRow() {
        var user = User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO).build();
        var periodEnd = LocalDateTime.now().plusDays(12);
        var subscription = Subscription.builder().user(user).provider("revenuecat")
                .status(SubscriptionStatus.ACTIVE).currentPeriodEnd(periodEnd).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        var status = service.statusFor(user);

        assertEquals(SubscriptionTier.PRO, status.tier());
        assertEquals(SubscriptionStatus.ACTIVE, status.status());
        assertEquals("revenuecat", status.provider());
        assertEquals(periodEnd, status.currentPeriodEnd());
    }

    @Test
    void grantSignupPromoIfEnabled_grantsProForConfiguredMonthsWhenEnabled() {
        var user = freeUser();
        var subscription = new CollaborativeProperties.Subscription();
        subscription.getPromo().setEnabled(true);
        subscription.getPromo().setMonths(6);
        when(collaborativeProperties.getSubscription()).thenReturn(subscription);
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        var before = LocalDateTime.now().plusMonths(6);
        var returned = service.grantSignupPromoIfEnabled(user);
        var after = LocalDateTime.now().plusMonths(6);

        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
        var captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("manual", saved.getProvider());
        assertTrue(!saved.getCurrentPeriodEnd().isBefore(before) && !saved.getCurrentPeriodEnd().isAfter(after));
        assertEquals(saved.getCurrentPeriodEnd(), returned);
    }

    @Test
    void grantSignupPromoIfEnabled_doesNothingWhenPromoDisabled() {
        var user = freeUser();
        var subscription = new CollaborativeProperties.Subscription();
        subscription.getPromo().setEnabled(false);
        when(collaborativeProperties.getSubscription()).thenReturn(subscription);

        var returned = service.grantSignupPromoIfEnabled(user);

        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        assertEquals(null, returned);
        verify(subscriptionRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void statusFor_freeUserWithoutRowHasNullLifecycleFields() {
        var user = freeUser();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        var status = service.statusFor(user);

        assertEquals(SubscriptionTier.FREE, status.tier());
        assertEquals(null, status.status());
        assertEquals(null, status.provider());
        assertEquals(null, status.currentPeriodEnd());
    }
}
