package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.model.PremiumGrant;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PremiumGrantKind;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.PremiumGrantRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumGrantServiceTest {

    @Mock private PremiumGrantRepository premiumGrantRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private PremiumGrantService service;

    private final UUID invitedId = UUID.randomUUID();

    private User user(SubscriptionTier tier) {
        return User.builder().id(UUID.randomUUID()).email("inviter@economizaai.app").subscriptionTier(tier).build();
    }

    private void grantsAreEchoed() {
        lenient().when(premiumGrantRepository.save(any(PremiumGrant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void subscriptionIs(User user, SubscriptionStatus status, String provider, LocalDateTime periodEnd) {
        when(subscriptionRepository.findByUserId(user.getId())).thenReturn(Optional.of(
                Subscription.builder().user(user).status(status).provider(provider).currentPeriodEnd(periodEnd).build()));
    }

    @Test
    void grantMonth_promoHolder_queuesAfterThePromoAndLeavesTheSubscriptionAlone() {
        var inviter = user(SubscriptionTier.PRO);
        var promoEnd = EntitlementClock.endOfBrazilDay(LocalDate.now().plusDays(50));
        subscriptionIs(inviter, SubscriptionStatus.ACTIVE, "manual", promoEnd);
        grantsAreEchoed();

        var grant = service.grantMonth(inviter, PremiumGrantKind.REFERRAL_FIRST, invitedId).orElseThrow();

        assertEquals(promoEnd.plusSeconds(1), grant.getStartsAt(), "starts the day after the promo ends");
        assertEquals(LocalDate.now().plusDays(51), EntitlementClock.brazilDayOf(grant.getStartsAt()));
        assertEquals(LocalDate.now().plusDays(51).plusMonths(1), EntitlementClock.brazilDayOf(grant.getEndsAt()));
        assertEquals(PremiumGrantKind.REFERRAL_FIRST, grant.getKind());
        assertEquals(invitedId, grant.getSourceUserId());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void grantMonth_paidSubscriber_startsWhenThePaidPeriodEnds() {
        var inviter = user(SubscriptionTier.PRO);
        var paidUntil = LocalDateTime.now().plusDays(12).withHour(15).withMinute(0).withSecond(0).withNano(0);
        subscriptionIs(inviter, SubscriptionStatus.ACTIVE, "revenuecat", paidUntil);
        grantsAreEchoed();

        var grant = service.grantMonth(inviter, PremiumGrantKind.REFERRAL_SUBSCRIPTION, invitedId).orElseThrow();

        assertFalse(grant.getStartsAt().isBefore(paidUntil), "a reward never overlaps the paid period");
        verify(subscriptionRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void grantMonth_secondReward_stacksAfterTheFirst() {
        var inviter = user(SubscriptionTier.PRO);
        var promoEnd = EntitlementClock.endOfBrazilDay(LocalDate.now().plusDays(50));
        var firstRewardEnd = EntitlementClock.monthAfter(LocalDateTime.now(), promoEnd).endsAt();
        subscriptionIs(inviter, SubscriptionStatus.ACTIVE, "manual", promoEnd);
        when(premiumGrantRepository.findLatestEndByUserId(inviter.getId())).thenReturn(firstRewardEnd);
        grantsAreEchoed();

        var second = service.grantMonth(inviter, PremiumGrantKind.REFERRAL_SUBSCRIPTION, invitedId).orElseThrow();

        assertEquals(firstRewardEnd, second.getStartsAt());
        assertEquals(EntitlementClock.monthAfter(LocalDateTime.now(), firstRewardEnd).endsAt(), second.getEndsAt());
    }

    @Test
    void grantMonth_freeUser_startsNowAndBecomesPro() {
        var inviter = user(SubscriptionTier.FREE);
        subscriptionIs(inviter, SubscriptionStatus.EXPIRED, "manual", LocalDateTime.now().minusDays(40));
        grantsAreEchoed();
        var before = LocalDateTime.now();

        var grant = service.grantMonth(inviter, PremiumGrantKind.REFERRAL_FIRST, invitedId).orElseThrow();

        assertFalse(grant.getStartsAt().isBefore(before.minusSeconds(1)));
        assertFalse(grant.getStartsAt().isAfter(LocalDateTime.now()));
        assertEquals(SubscriptionTier.PRO, inviter.getSubscriptionTier());
        verify(userRepository).save(inviter);
    }

    @Test
    void grantMonth_isIdempotentPerInvitedPerson() {
        var inviter = user(SubscriptionTier.PRO);
        when(premiumGrantRepository.existsByUserIdAndSourceUserId(inviter.getId(), invitedId)).thenReturn(true);

        var grant = service.grantMonth(inviter, PremiumGrantKind.REFERRAL_SUBSCRIPTION, invitedId);

        assertTrue(grant.isEmpty());
        verify(premiumGrantRepository, never()).save(any());
    }

    @Test
    void reconcileTiers_promotesWhenAWindowStartsAndDowngradesWhenAllEnded() {
        var rewardJustStarted = user(SubscriptionTier.FREE);
        var rewardOver = user(SubscriptionTier.PRO);
        when(premiumGrantRepository.findFreeUsersWithActiveGrant(any())).thenReturn(List.of(rewardJustStarted));
        when(premiumGrantRepository.findProUsersWithOnlyEndedGrants(any())).thenReturn(List.of(rewardOver));

        service.reconcileTiers();

        assertEquals(SubscriptionTier.PRO, rewardJustStarted.getSubscriptionTier());
        assertEquals(SubscriptionTier.FREE, rewardOver.getSubscriptionTier());
        verify(userRepository).save(rewardJustStarted);
        verify(userRepository).save(rewardOver);
    }
}
