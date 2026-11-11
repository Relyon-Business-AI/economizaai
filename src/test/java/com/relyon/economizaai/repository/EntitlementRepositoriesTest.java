package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.LaunchPromoWarning;
import com.relyon.economizaai.model.PremiumGrant;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.Referral;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.PremiumGrantKind;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queries behind promo warnings, reward windows, referrals and the monthly receipt count. */
@DataJpaTest
@ActiveProfiles("test")
class EntitlementRepositoriesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.DECEMBER, 16, 10, 0);
    private static final LocalDateTime PROMO_END = LocalDateTime.of(2027, Month.JANUARY, 1, 2, 59, 59);
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);

    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PremiumGrantRepository premiumGrantRepository;
    @Autowired private LaunchPromoWarningRepository launchPromoWarningRepository;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;

    private int sequence;

    private User user(SubscriptionTier tier) {
        sequence++;
        var household = householdRepository.save(Household.builder().inviteCode("ENT" + (100 + sequence)).build());
        return userRepository.save(User.builder()
                .name("Tester " + sequence).email("test" + (71000 + sequence) + "@economizaai.app").password("x")
                .subscriptionTier(tier).household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0").acceptedLegalAt(NOW)
                .build());
    }

    private Subscription promoSubscription(User user) {
        return subscriptionRepository.save(Subscription.builder().user(user)
                .status(SubscriptionStatus.ACTIVE).provider("manual").currentPeriodEnd(PROMO_END)
                .launchPromoGrantedAt(NOW.minusMonths(1)).launchPromoUntil(PROMO_END).build());
    }

    private PremiumGrant grant(User user, LocalDateTime startsAt, LocalDateTime endsAt) {
        return premiumGrantRepository.save(PremiumGrant.builder().user(user)
                .kind(PremiumGrantKind.REFERRAL_SUBSCRIPTION).sourceUserId(UUID.randomUUID())
                .startsAt(startsAt).endsAt(endsAt).build());
    }

    private List<UUID> holdersToWarn(int milestoneDays) {
        return subscriptionRepository
                .findLaunchPromoHoldersToWarn(PROMO_END, milestoneDays, BEFORE_FIRST_ID, PageRequest.of(0, 50))
                .stream().map(subscription -> subscription.getUser().getId()).toList();
    }

    @Test
    void findLaunchPromoHoldersToWarn_returnsOnlyPromoHoldersNotYetWarnedForTheMilestone() {
        var promoHolder = user(SubscriptionTier.PRO);
        promoSubscription(promoHolder);

        var alreadyWarned = user(SubscriptionTier.PRO);
        promoSubscription(alreadyWarned);
        launchPromoWarningRepository.save(LaunchPromoWarning.builder()
                .userId(alreadyWarned.getId()).milestoneDays(15).build());

        var paidSubscriber = user(SubscriptionTier.PRO);
        subscriptionRepository.save(Subscription.builder().user(paidSubscriber)
                .status(SubscriptionStatus.ACTIVE).provider("revenuecat").currentPeriodEnd(NOW.plusDays(20))
                .launchPromoGrantedAt(NOW.minusMonths(1)).launchPromoUntil(PROMO_END).build());

        var adminGrant = user(SubscriptionTier.PRO);
        subscriptionRepository.save(Subscription.builder().user(adminGrant)
                .status(SubscriptionStatus.ACTIVE).provider("manual").currentPeriodEnd(null).build());

        var expiredPromo = user(SubscriptionTier.FREE);
        var expired = promoSubscription(expiredPromo);
        expired.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(expired);

        var keepsPremiumThroughAReward = user(SubscriptionTier.PRO);
        promoSubscription(keepsPremiumThroughAReward);
        grant(keepsPremiumThroughAReward, PROMO_END.plusSeconds(1), PROMO_END.plusMonths(1));

        assertEquals(List.of(promoHolder.getId()), holdersToWarn(15));
        // A different milestone is owed to both promo holders, whatever was sent for D-15.
        assertEquals(2, holdersToWarn(7).size());
        assertTrue(holdersToWarn(7).containsAll(List.of(promoHolder.getId(), alreadyWarned.getId())));
    }

    @Test
    void findLaunchPromoHoldersToWarn_pagesByKeysetWithoutRepeating() {
        var first = promoSubscription(user(SubscriptionTier.PRO));
        var second = promoSubscription(user(SubscriptionTier.PRO));
        var third = promoSubscription(user(SubscriptionTier.PRO));

        var pageOne = subscriptionRepository.findLaunchPromoHoldersToWarn(
                PROMO_END, 15, BEFORE_FIRST_ID, PageRequest.of(0, 2));
        var pageTwo = subscriptionRepository.findLaunchPromoHoldersToWarn(
                PROMO_END, 15, pageOne.get(1).getId(), PageRequest.of(0, 2));

        assertEquals(2, pageOne.size());
        assertEquals(1, pageTwo.size());
        var seen = List.of(pageOne.get(0).getId(), pageOne.get(1).getId(), pageTwo.get(0).getId());
        assertTrue(seen.containsAll(List.of(first.getId(), second.getId(), third.getId())));
    }

    @Test
    void premiumGrantWindows_driveActiveLatestEndAndReconciliation() {
        var rewardRunning = user(SubscriptionTier.FREE);
        grant(rewardRunning, NOW.minusDays(3), NOW.plusDays(27));

        var rewardQueued = user(SubscriptionTier.FREE);
        grant(rewardQueued, NOW.plusDays(10), NOW.plusDays(40));

        var rewardOver = user(SubscriptionTier.PRO);
        grant(rewardOver, NOW.minusDays(60), NOW.minusDays(30));

        var rewardOverButStillSubscribed = user(SubscriptionTier.PRO);
        grant(rewardOverButStillSubscribed, NOW.minusDays(60), NOW.minusDays(30));
        promoSubscription(rewardOverButStillSubscribed);

        var stackedRewards = user(SubscriptionTier.PRO);
        grant(stackedRewards, NOW.minusDays(40), NOW.minusDays(10));
        grant(stackedRewards, NOW.minusDays(10), NOW.plusDays(20));

        assertTrue(premiumGrantRepository.existsActive(rewardRunning.getId(), NOW));
        assertFalse(premiumGrantRepository.existsActive(rewardQueued.getId(), NOW));
        assertFalse(premiumGrantRepository.existsActive(rewardOver.getId(), NOW));
        assertEquals(NOW.plusDays(20), premiumGrantRepository.findLatestEndByUserId(stackedRewards.getId()));
        assertNull(premiumGrantRepository.findLatestEndByUserId(user(SubscriptionTier.FREE).getId()));

        var toPromote = premiumGrantRepository.findFreeUsersWithActiveGrant(NOW).stream().map(User::getId).toList();
        var toDowngrade = premiumGrantRepository.findProUsersWithOnlyEndedGrants(NOW).stream().map(User::getId).toList();
        assertEquals(List.of(rewardRunning.getId()), toPromote);
        assertEquals(List.of(rewardOver.getId()), toDowngrade);
    }

    @Test
    void referral_findsTheInviterOfAnInvitedAccountAndCountsPerInviter() {
        var inviter = user(SubscriptionTier.FREE);
        var qualifiedFriend = user(SubscriptionTier.FREE);
        var pendingFriend = user(SubscriptionTier.FREE);
        referralRepository.save(Referral.builder().inviter(inviter).invited(qualifiedFriend).qualifiedAt(NOW).build());
        referralRepository.save(Referral.builder().inviter(inviter).invited(pendingFriend).build());

        var found = referralRepository.findByInvitedIdWithInviter(pendingFriend.getId()).orElseThrow();

        assertEquals(inviter.getId(), found.getInviter().getId());
        assertTrue(referralRepository.existsByInvitedId(qualifiedFriend.getId()));
        assertFalse(referralRepository.existsByInvitedId(inviter.getId()));
        assertTrue(referralRepository.existsByInviterIdAndInvitedId(inviter.getId(), pendingFriend.getId()));
        assertFalse(referralRepository.existsByInviterIdAndInvitedId(pendingFriend.getId(), inviter.getId()));
        assertEquals(2, referralRepository.countByInviterId(inviter.getId()));
        assertEquals(1, referralRepository.countByInviterIdAndQualifiedAtIsNotNull(inviter.getId()));
    }

    @Test
    void monthlyReceiptCount_ignoresHouseholdSplitCopies() {
        var owner = user(SubscriptionTier.FREE);
        receiptRepository.save(receipt(owner, "43000000000000000000000000000000000000000001", false));
        receiptRepository.save(receipt(owner, "43000000000000000000000000000000000000000002", false));
        receiptRepository.save(receipt(owner, "43000000000000000000000000000000000000000003", true));
        var since = LocalDateTime.now().minusHours(1);

        assertEquals(2, receiptRepository.countByUserIdAndSplitCopyFalseAndCreatedAtGreaterThanEqual(owner.getId(), since));
        assertEquals(3, receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(owner.getId(), since));
    }

    private Receipt receipt(User owner, String chave, boolean splitCopy) {
        return Receipt.builder()
                .user(owner).household(owner.getHousehold())
                .chaveAcesso(chave).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .splitCopy(splitCopy)
                .build();
    }
}
