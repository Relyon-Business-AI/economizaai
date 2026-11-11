package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.PremiumGrantRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private CollaborativeProperties collaborativeProperties;
    @Mock private PremiumGrantRepository premiumGrantRepository;
    @Mock private SubscriptionDeletionAlertService subscriptionDeletionAlertService;

    @InjectMocks private SubscriptionService service;

    private User freeUser() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.FREE).build();
    }

    private User proUser() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .subscriptionTier(SubscriptionTier.PRO).build();
    }

    private LocalDate enablePromoUntil(LocalDate until) {
        var subscription = new CollaborativeProperties.Subscription();
        subscription.getPromo().setEnabled(true);
        subscription.getPromo().setUntil(until);
        when(collaborativeProperties.getSubscription()).thenReturn(subscription);
        return until;
    }

    private void savesAreEchoed() {
        lenient().when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- activatePro / cancel / status ----------

    @Test
    void activatePro_insertsSubscriptionAndSetsTierWhenNoneExists() {
        var user = freeUser();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        savesAreEchoed();
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
    void activatePro_upsertsExistingSubscriptionAndClearsNonRenewing() {
        var user = freeUser();
        var existing = Subscription.builder().user(user)
                .status(SubscriptionStatus.CANCELED).provider("old").cancelAtPeriodEnd(true).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));
        savesAreEchoed();

        service.activatePro(user, "mercadopago", "mp_9", null);

        assertEquals(SubscriptionStatus.ACTIVE, existing.getStatus());
        assertEquals("mercadopago", existing.getProvider());
        assertFalse(existing.isCancelAtPeriodEnd());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
    }

    @Test
    void cancel_marksSubscriptionCanceledAndDropsTier() {
        var user = proUser();
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        service.cancel(user);

        assertEquals(SubscriptionStatus.CANCELED, existing.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(subscriptionRepository).save(existing);
        verify(userRepository).save(user);
    }

    @Test
    void cancel_dropsTierEvenWhenNoSubscriptionRow() {
        var user = proUser();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        service.cancel(user);

        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(userRepository).save(user);
    }

    @Test
    void statusFor_reflectsSubscriptionRow() {
        var user = proUser();
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
    void statusFor_exposesNonRenewingFlagAndRewardWindow() {
        var user = proUser();
        var rewardEnd = LocalDateTime.now().plusDays(70);
        var subscription = Subscription.builder().user(user).provider("revenuecat")
                .status(SubscriptionStatus.ACTIVE).currentPeriodEnd(LocalDateTime.now().plusDays(12))
                .cancelAtPeriodEnd(true).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));
        when(premiumGrantRepository.findLatestEndByUserId(user.getId())).thenReturn(rewardEnd);

        var status = service.statusFor(user);

        assertTrue(status.cancelAtPeriodEnd());
        assertEquals(rewardEnd, status.rewardPremiumUntil());
    }

    @Test
    void statusFor_freeUserWithoutRowHasNullLifecycleFields() {
        var user = freeUser();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        var status = service.statusFor(user);

        assertEquals(SubscriptionTier.FREE, status.tier());
        assertNull(status.status());
        assertNull(status.provider());
        assertNull(status.currentPeriodEnd());
        assertFalse(status.cancelAtPeriodEnd());
        assertNull(status.rewardPremiumUntil());
    }

    // ---------- expiry ----------

    @Test
    void expire_marksSubscriptionExpiredAndDropsTier() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(LocalDateTime.now().minusDays(1)).build();

        service.expire(subscription);

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(subscriptionRepository).save(subscription);
        verify(userRepository).save(user);
    }

    @Test
    void expire_keepsProWhileARewardWindowIsRunning() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(LocalDateTime.now().minusMinutes(5)).build();
        when(premiumGrantRepository.existsActive(eq(user.getId()), any())).thenReturn(true);

        service.expire(subscription);

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
    }

    @Test
    void expire_paidSubscriptionFallsBackToLaunchPromoStillRunning() {
        var user = proUser();
        var promoEnd = LocalDateTime.now().plusDays(20);
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("revenuecat").providerRef("pro_monthly").cancelAtPeriodEnd(true)
                .currentPeriodEnd(LocalDateTime.now().minusDays(1))
                .launchPromoGrantedAt(LocalDateTime.now().minusMonths(1)).launchPromoUntil(promoEnd).build();
        savesAreEchoed();

        service.expire(subscription);

        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertEquals("manual", subscription.getProvider());
        assertNull(subscription.getProviderRef());
        assertEquals(promoEnd, subscription.getCurrentPeriodEnd());
        assertFalse(subscription.isCancelAtPeriodEnd());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
    }

    @Test
    void expire_paidSubscriptionGoesFreeWhenLaunchPromoIsOver() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("mercadopago").currentPeriodEnd(LocalDateTime.now().minusDays(4))
                .launchPromoGrantedAt(LocalDateTime.now().minusMonths(3))
                .launchPromoUntil(LocalDateTime.now().minusDays(10)).build();

        service.expire(subscription);

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
    }

    @Test
    void expire_promoRowNeverFallsBackToItself() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(LocalDateTime.now().minusMinutes(1))
                .launchPromoUntil(LocalDateTime.now().plusDays(1)).build();

        service.expire(subscription);

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
    }

    // ---------- provider cancellation / expiration ----------

    @Test
    void markNonRenewing_keepsProAndAccessUntilPeriodEnd() {
        var user = proUser();
        var periodEnd = LocalDateTime.now().plusDays(18);
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("revenuecat").currentPeriodEnd(periodEnd).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        service.markNonRenewing(user, "revenuecat");

        assertTrue(subscription.isCancelAtPeriodEnd());
        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertEquals(periodEnd, subscription.getCurrentPeriodEnd());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
        verify(userRepository, never()).save(any());
    }

    @Test
    void markNonRenewing_ignoresASubscriptionFromAnotherProvider() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(LocalDateTime.now().plusDays(18)).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        service.markNonRenewing(user, "revenuecat");

        assertFalse(subscription.isCancelAtPeriodEnd());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void markNonRenewing_withoutAPeriodEndEndsTheSubscriptionNow() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("mercadopago").currentPeriodEnd(null).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        service.markNonRenewing(user, "mercadopago");

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
    }

    @Test
    void endProviderSubscription_dropsToFreeWithoutPromo() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("revenuecat").currentPeriodEnd(LocalDateTime.now().minusHours(1)).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        service.endProviderSubscription(user, "revenuecat");

        assertEquals(SubscriptionStatus.EXPIRED, subscription.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
    }

    @Test
    void endProviderSubscription_fallsBackToLaunchPromoStillRunning() {
        var user = proUser();
        var promoEnd = LocalDateTime.now().plusDays(30);
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("revenuecat").currentPeriodEnd(LocalDateTime.now().minusHours(1))
                .launchPromoGrantedAt(LocalDateTime.now().minusDays(40)).launchPromoUntil(promoEnd).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));
        savesAreEchoed();

        service.endProviderSubscription(user, "revenuecat");

        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertEquals("manual", subscription.getProvider());
        assertEquals(promoEnd, subscription.getCurrentPeriodEnd());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
    }

    @Test
    void endProviderSubscription_neverTouchesAPromoRow() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(LocalDateTime.now().plusDays(30)).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        service.endProviderSubscription(user, "revenuecat");

        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
        verify(subscriptionRepository, never()).save(any());
    }

    // ---------- launch promo ----------

    @Test
    void grantSignupPromoIfEnabled_grantsProForConfiguredMonthsWhenNoFixedDate() {
        var user = freeUser();
        var subscription = new CollaborativeProperties.Subscription();
        subscription.getPromo().setEnabled(true);
        subscription.getPromo().setMonths(6);
        when(collaborativeProperties.getSubscription()).thenReturn(subscription);
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        savesAreEchoed();

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
    void grantSignupPromoIfEnabled_grantsUntilEndOfTheFixedBrazilDayAndRemembersIt() {
        var user = freeUser();
        var fixedDate = enablePromoUntil(LocalDate.now().plusDays(45));
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        savesAreEchoed();

        var returned = service.grantSignupPromoIfEnabled(user);

        assertEquals(EntitlementClock.endOfBrazilDay(fixedDate), returned);
        assertEquals(fixedDate, EntitlementClock.brazilDayOf(returned));
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
        var captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertNotNull(captor.getValue().getLaunchPromoGrantedAt());
        assertEquals(returned, captor.getValue().getLaunchPromoUntil());
    }

    @Test
    void grantSignupPromoIfEnabled_isGrantedOnlyOncePerUser() {
        var user = freeUser();
        enablePromoUntil(LocalDate.now().plusDays(45));
        // The promo was granted before and then lost (e.g. admin set FREE): confirming a "first"
        // receipt again must not re-fire it.
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.CANCELED).provider("manual")
                .launchPromoGrantedAt(LocalDateTime.now().minusDays(30))
                .launchPromoUntil(LocalDateTime.now().plusDays(45)).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        var returned = service.grantSignupPromoIfEnabled(user);

        assertNull(returned);
        assertEquals(SubscriptionStatus.CANCELED, existing.getStatus());
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(subscriptionRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void grantSignupPromoIfEnabled_neverReplacesAnActivePaidSubscription() {
        var user = proUser();
        enablePromoUntil(LocalDate.now().plusDays(45));
        var paidUntil = LocalDateTime.now().plusDays(10);
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("revenuecat").providerRef("pro_monthly").currentPeriodEnd(paidUntil).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        var returned = service.grantSignupPromoIfEnabled(user);

        assertNull(returned, "nothing changed for the user — no promo notice");
        assertEquals("revenuecat", existing.getProvider());
        assertEquals("pro_monthly", existing.getProviderRef());
        assertEquals(paidUntil, existing.getCurrentPeriodEnd());
        // ...but the promo is remembered, so the paid subscription can fall back to it later.
        assertNotNull(existing.getLaunchPromoGrantedAt());
        assertNotNull(existing.getLaunchPromoUntil());
        verify(subscriptionRepository).save(existing);
    }

    @Test
    void grantSignupPromoIfEnabled_neverReplacesAnOpenEndedAdminGrant() {
        var user = proUser();
        enablePromoUntil(LocalDate.now().plusDays(45));
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(null).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        var returned = service.grantSignupPromoIfEnabled(user);

        assertNull(returned);
        assertNull(existing.getCurrentPeriodEnd(), "open-ended grant must stay open-ended");
    }

    @Test
    void grantSignupPromoIfEnabled_neverShortensAPeriodThatEndsLater() {
        var user = proUser();
        enablePromoUntil(LocalDate.now().plusDays(45));
        var laterEnd = LocalDateTime.now().plusDays(200);
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("manual").currentPeriodEnd(laterEnd).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        var returned = service.grantSignupPromoIfEnabled(user);

        assertNull(returned);
        assertEquals(laterEnd, existing.getCurrentPeriodEnd());
    }

    @Test
    void grantSignupPromoIfEnabled_replacesAnInactiveSubscription() {
        var user = freeUser();
        var fixedDate = enablePromoUntil(LocalDate.now().plusDays(45));
        var existing = Subscription.builder().user(user).status(SubscriptionStatus.EXPIRED)
                .provider("revenuecat").currentPeriodEnd(LocalDateTime.now().minusDays(3)).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));
        savesAreEchoed();

        var returned = service.grantSignupPromoIfEnabled(user);

        assertEquals(EntitlementClock.endOfBrazilDay(fixedDate), returned);
        assertEquals("manual", existing.getProvider());
        assertEquals(SubscriptionStatus.ACTIVE, existing.getStatus());
        assertEquals(SubscriptionTier.PRO, user.getSubscriptionTier());
    }

    @Test
    void grantSignupPromoIfEnabled_doesNothingWhenFixedDateHasPassed() {
        var user = freeUser();
        enablePromoUntil(LocalDate.now().minusDays(2));

        var returned = service.grantSignupPromoIfEnabled(user);

        assertNull(returned);
        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void grantSignupPromoIfEnabled_doesNothingWhenPromoDisabled() {
        var user = freeUser();
        var subscription = new CollaborativeProperties.Subscription();
        subscription.getPromo().setEnabled(false);
        when(collaborativeProperties.getSubscription()).thenReturn(subscription);

        var returned = service.grantSignupPromoIfEnabled(user);

        assertEquals(SubscriptionTier.FREE, user.getSubscriptionTier());
        assertNull(returned);
        verify(subscriptionRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    // ---------- account deletion ----------

    @Test
    void warnIfDeletingPaidSubscriber_alertsForAnActivePaidSubscription() {
        var user = proUser();
        var subscription = Subscription.builder().user(user).status(SubscriptionStatus.ACTIVE)
                .provider("revenuecat").currentPeriodEnd(LocalDateTime.now().plusDays(9)).build();
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(subscription));

        service.warnIfDeletingPaidSubscriber(user);

        verify(subscriptionDeletionAlertService).notifyPaidSubscriberDeleted(user, subscription);
    }

    @Test
    void warnIfDeletingPaidSubscriber_staysQuietForPromoOrInactiveSubscriptions() {
        var promoUser = proUser();
        when(subscriptionRepository.findByUserId(promoUser.getId())).thenReturn(Optional.of(
                Subscription.builder().user(promoUser).status(SubscriptionStatus.ACTIVE).provider("manual").build()));
        var lapsedUser = freeUser();
        when(subscriptionRepository.findByUserId(lapsedUser.getId())).thenReturn(Optional.of(
                Subscription.builder().user(lapsedUser).status(SubscriptionStatus.EXPIRED).provider("revenuecat").build()));

        service.warnIfDeletingPaidSubscriber(promoUser);
        service.warnIfDeletingPaidSubscriber(lapsedUser);

        verifyNoInteractions(subscriptionDeletionAlertService);
    }
}
