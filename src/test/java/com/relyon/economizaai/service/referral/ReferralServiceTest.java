package com.relyon.economizaai.service.referral;

import com.relyon.economizaai.exception.ReferralCodeRejectedException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.PremiumGrant;
import com.relyon.economizaai.model.Referral;
import com.relyon.economizaai.model.ReferralCode;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.PremiumGrantKind;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.PremiumGrantRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ReferralCodeRepository;
import com.relyon.economizaai.repository.ReferralRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.subscription.EntitlementClock;
import com.relyon.economizaai.service.subscription.PremiumGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    private static final String SHARE_BASE_URL = "https://economizaai.app/?ref=";

    @Mock private ReferralCodeRepository referralCodeRepository;
    @Mock private ReferralRepository referralRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationAuditRepository priceObservationAuditRepository;
    @Mock private PremiumGrantRepository premiumGrantRepository;
    @Mock private PremiumGrantService premiumGrantService;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService localizedMessageService;
    @Mock private PlatformTransactionManager transactionManager;

    private ReferralService service;

    private final User inviter = userInOwnHousehold("inviter@economizaai.app");
    private final User invited = userInOwnHousehold("invited@economizaai.app");
    private final UUID receiptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReferralService(referralCodeRepository, referralRepository, userRepository, receiptRepository,
                priceObservationAuditRepository, premiumGrantRepository, premiumGrantService, notificationService,
                localizedMessageService, transactionManager, SHARE_BASE_URL);
        lenient().when(localizedMessageService.translate(anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(localizedMessageService.translate(anyString(), any(Locale.class), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "|" + invocation.getArgument(2)
                        + "|" + invocation.getArgument(3));
    }

    private static User userInOwnHousehold(String email) {
        return User.builder().id(UUID.randomUUID()).email(email).locale("pt")
                .household(Household.builder().id(UUID.randomUUID()).build()).build();
    }

    private Referral openReferral() {
        var referral = Referral.builder().id(UUID.randomUUID()).inviter(inviter).invited(invited).build();
        when(referralRepository.findByInvitedIdWithInviter(invited.getId())).thenReturn(Optional.of(referral));
        return referral;
    }

    private void codeBelongsTo(String code, User owner) {
        when(referralCodeRepository.findByCode(code)).thenReturn(Optional.of(
                ReferralCode.builder().userId(owner.getId()).code(code).build()));
    }

    private PremiumGrant januaryGrant(PremiumGrantKind kind) {
        var startsAt = EntitlementClock.endOfBrazilDay(LocalDate.of(2026, 12, 31)).plusSeconds(1);
        return PremiumGrant.builder().user(inviter).kind(kind).sourceUserId(invited.getId())
                .startsAt(startsAt)
                .endsAt(EntitlementClock.monthAfter(startsAt.minusDays(1), startsAt).endsAt())
                .build();
    }

    // ---------- code + status ----------

    @Test
    void statusFor_createsAStableUnambiguousCodeOnFirstRead() {
        when(referralCodeRepository.findByUserId(inviter.getId())).thenReturn(Optional.empty());
        when(referralCodeRepository.saveAndFlush(any(ReferralCode.class))).thenAnswer(inv -> inv.getArgument(0));

        var status = service.statusFor(inviter);

        assertTrue(status.code().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{7}"), status.code());
        assertEquals(SHARE_BASE_URL + status.code(), status.shareUrl());
        var saved = ArgumentCaptor.forClass(ReferralCode.class);
        verify(referralCodeRepository).saveAndFlush(saved.capture());
        assertEquals(inviter.getId(), saved.getValue().getUserId());
    }

    @Test
    void statusFor_reusesTheExistingCodeAndReportsCounters() {
        when(referralCodeRepository.findByUserId(inviter.getId())).thenReturn(Optional.of(
                ReferralCode.builder().userId(inviter.getId()).code("ABC2345").build()));
        when(referralRepository.countByInviterId(inviter.getId())).thenReturn(4L);
        when(referralRepository.countByInviterIdAndQualifiedAtIsNotNull(inviter.getId())).thenReturn(2L);
        when(premiumGrantRepository.countByUserId(inviter.getId())).thenReturn(3L);
        when(premiumGrantRepository.existsByUserIdAndKind(inviter.getId(), PremiumGrantKind.REFERRAL_FIRST)).thenReturn(true);
        when(referralRepository.existsByInvitedId(inviter.getId())).thenReturn(false);
        when(receiptRepository.countByUserIdAndStatus(inviter.getId(), ReceiptStatus.CONFIRMED)).thenReturn(0L);

        var status = service.statusFor(inviter);

        assertEquals("ABC2345", status.code());
        assertEquals("https://economizaai.app/?ref=ABC2345", status.shareUrl());
        assertEquals(4, status.invitedCount());
        assertEquals(2, status.qualifiedCount());
        assertEquals(3, status.rewardedMonths());
        assertTrue(status.firstRewardUsed());
        assertFalse(status.referredBy());
        assertTrue(status.canRedeemCode());
        verify(referralCodeRepository, never()).saveAndFlush(any());
    }

    @Test
    void statusFor_cannotRedeemOnceReferredOrWithAConfirmedReceipt() {
        when(referralCodeRepository.findByUserId(any())).thenReturn(Optional.of(
                ReferralCode.builder().code("ABC2345").build()));
        when(referralRepository.existsByInvitedId(invited.getId())).thenReturn(true);
        when(referralRepository.existsByInvitedId(inviter.getId())).thenReturn(false);
        when(receiptRepository.countByUserIdAndStatus(inviter.getId(), ReceiptStatus.CONFIRMED)).thenReturn(2L);

        var alreadyReferred = service.statusFor(invited);
        var hasReceipts = service.statusFor(inviter);

        assertTrue(alreadyReferred.referredBy());
        assertFalse(alreadyReferred.canRedeemCode());
        assertFalse(hasReceipts.referredBy());
        assertFalse(hasReceipts.canRedeemCode());
    }

    // ---------- attribution at signup ----------

    @Test
    void attachOnSignup_linksTheNewAccountToTheCodeOwner() {
        codeBelongsTo("ABC2345", inviter);
        when(userRepository.getReferenceById(inviter.getId())).thenReturn(inviter);

        service.attachOnSignup(invited, " abc2345 ");

        var saved = ArgumentCaptor.forClass(Referral.class);
        verify(referralRepository).save(saved.capture());
        assertEquals(inviter, saved.getValue().getInviter());
        assertEquals(invited, saved.getValue().getInvited());
        assertNull(saved.getValue().getQualifiedAt());
    }

    @Test
    void attachOnSignup_silentlyIgnoresMissingUnknownOrOwnCodes() {
        when(referralCodeRepository.findByCode("NOPE234")).thenReturn(Optional.empty());
        codeBelongsTo("MINE234", invited);

        assertDoesNotThrow(() -> {
            service.attachOnSignup(invited, null);
            service.attachOnSignup(invited, "   ");
            service.attachOnSignup(invited, "NOPE234");
            service.attachOnSignup(invited, "MINE234");
        });

        verify(referralRepository, never()).save(any());
    }

    // ---------- redeem ----------

    @Test
    void redeem_linksAnAccountThatHasNoConfirmedReceiptYet() {
        codeBelongsTo("ABC2345", inviter);
        when(userRepository.getReferenceById(inviter.getId())).thenReturn(inviter);
        when(receiptRepository.countByUserIdAndStatus(invited.getId(), ReceiptStatus.CONFIRMED)).thenReturn(0L);

        service.redeem(invited, "abc2345");

        verify(referralRepository).save(any(Referral.class));
    }

    @Test
    void redeem_rejectsAnUnknownCode() {
        when(referralCodeRepository.findByCode("NOPE234")).thenReturn(Optional.empty());

        var rejection = assertThrows(ReferralCodeRejectedException.class, () -> service.redeem(invited, "NOPE234"));

        assertEquals("referral.code.invalid", rejection.getMessageKey());
        assertFalse(rejection.isAccountNoLongerEligible());
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeem_rejectsTheUsersOwnCode() {
        codeBelongsTo("MINE234", invited);

        var rejection = assertThrows(ReferralCodeRejectedException.class, () -> service.redeem(invited, "MINE234"));

        assertEquals("referral.code.own", rejection.getMessageKey());
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeem_rejectsAnAccountThatWasAlreadyReferred() {
        codeBelongsTo("ABC2345", inviter);
        when(referralRepository.existsByInvitedId(invited.getId())).thenReturn(true);

        var rejection = assertThrows(ReferralCodeRejectedException.class, () -> service.redeem(invited, "ABC2345"));

        assertEquals("referral.redeem.already_referred", rejection.getMessageKey());
        assertTrue(rejection.isAccountNoLongerEligible());
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeem_isTooLateAfterTheFirstConfirmedReceipt() {
        codeBelongsTo("ABC2345", inviter);
        when(receiptRepository.countByUserIdAndStatus(invited.getId(), ReceiptStatus.CONFIRMED)).thenReturn(1L);

        var rejection = assertThrows(ReferralCodeRejectedException.class, () -> service.redeem(invited, "ABC2345"));

        assertEquals("referral.redeem.too_late", rejection.getMessageKey());
        assertTrue(rejection.isAccountNoLongerEligible());
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeem_rejectsTwoAccountsReferringEachOther() {
        codeBelongsTo("ABC2345", inviter);
        // `invited` had invited `inviter` first.
        when(referralRepository.existsByInviterIdAndInvitedId(invited.getId(), inviter.getId())).thenReturn(true);

        assertThrows(ReferralCodeRejectedException.class, () -> service.redeem(invited, "ABC2345"));

        verify(referralRepository, never()).save(any());
    }

    // ---------- qualification matrix ----------

    @Test
    void qualify_firstQualifiedReferralGrantsOneMonthAndNotifiesTheInviter() {
        var referral = openReferral();
        when(priceObservationAuditRepository.existsByReceiptId(receiptId)).thenReturn(true);
        when(premiumGrantRepository.existsByUserIdAndKind(inviter.getId(), PremiumGrantKind.REFERRAL_FIRST)).thenReturn(false);
        when(premiumGrantService.grantMonth(inviter, PremiumGrantKind.REFERRAL_FIRST, invited.getId()))
                .thenReturn(Optional.of(januaryGrant(PremiumGrantKind.REFERRAL_FIRST)));

        service.qualifyAfterCommit(invited.getId(), receiptId);

        assertNotNull(referral.getQualifiedAt());
        assertEquals(receiptId, referral.getQualifyingReceiptId());
        verify(referralRepository).save(referral);
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        var payload = captor.getValue();
        assertEquals(inviter, payload.user());
        assertEquals(NotificationType.SYSTEM, payload.type());
        assertEquals("notification.referral_first_reward.title", payload.title());
        assertEquals("notification.referral_first_reward.body|01/01/2027|31/01/2027", payload.body());
        assertEquals("REFERRAL_REWARD", payload.extras().get("kind"));
        assertEquals("2027-01-01", payload.extras().get("startsOn"));
        assertEquals("2027-01-31", payload.extras().get("endsOn"));
    }

    @Test
    void qualify_receiptThatDidNotContributeToTheIndexDoesNotCount() {
        // Photo-origin, XML without verified authenticity, a chave another household already
        // scanned, or a user opted out of contribution: none of them produce a price observation.
        var referral = openReferral();
        when(priceObservationAuditRepository.existsByReceiptId(receiptId)).thenReturn(false);

        service.qualifyAfterCommit(invited.getId(), receiptId);

        assertNull(referral.getQualifiedAt());
        verify(referralRepository, never()).save(any());
        verifyNoInteractions(premiumGrantService, notificationService);
    }

    @Test
    void qualify_invitedLivingInTheInvitersHouseholdDoesNotCount() {
        var referral = openReferral();
        invited.setHousehold(inviter.getHousehold());
        when(priceObservationAuditRepository.existsByReceiptId(receiptId)).thenReturn(true);

        service.qualifyAfterCommit(invited.getId(), receiptId);

        assertNull(referral.getQualifiedAt());
        verifyNoInteractions(premiumGrantService, notificationService);
    }

    @Test
    void qualify_accountWithoutReferralIsANoOp() {
        when(referralRepository.findByInvitedIdWithInviter(invited.getId())).thenReturn(Optional.empty());

        service.qualifyAfterCommit(invited.getId(), receiptId);

        verifyNoInteractions(priceObservationAuditRepository, premiumGrantService, notificationService);
    }

    @Test
    void qualify_alreadyQualifiedReferralIsNotQualifiedAgain() {
        var referral = openReferral();
        referral.setQualifiedAt(LocalDateTime.now().minusDays(3));

        service.qualifyAfterCommit(invited.getId(), receiptId);

        verify(referralRepository, never()).save(any());
        verifyNoInteractions(premiumGrantService, notificationService);
    }

    @Test
    void qualify_firstRewardHappensOnlyOncePerInviter() {
        var secondReferral = openReferral();
        when(priceObservationAuditRepository.existsByReceiptId(receiptId)).thenReturn(true);
        when(premiumGrantRepository.existsByUserIdAndKind(inviter.getId(), PremiumGrantKind.REFERRAL_FIRST)).thenReturn(true);

        service.qualifyAfterCommit(invited.getId(), receiptId);

        assertNotNull(secondReferral.getQualifiedAt(), "it still counts as a qualified referral");
        verifyNoInteractions(premiumGrantService, notificationService);
    }

    @Test
    void qualify_neverPropagatesAFailure() {
        when(referralRepository.findByInvitedIdWithInviter(invited.getId()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> service.qualifyAfterCommit(invited.getId(), receiptId));
    }

    // ---------- subscription-based rewards ----------

    @Test
    void subscription_invitedFriendSubscribingGrantsOneMonthAndNotifies() {
        var referral = openReferral();
        when(premiumGrantService.grantMonth(inviter, PremiumGrantKind.REFERRAL_SUBSCRIPTION, invited.getId()))
                .thenReturn(Optional.of(januaryGrant(PremiumGrantKind.REFERRAL_SUBSCRIPTION)));

        service.rewardInviterForSubscription(invited);

        assertNotNull(referral.getSubscribedAt());
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(captor.capture());
        assertEquals(inviter, captor.getValue().user());
        assertEquals("notification.referral_subscription_reward.title", captor.getValue().title());
        assertEquals("REFERRAL_SUBSCRIPTION", captor.getValue().extras().get("rewardKind"));
    }

    @Test
    void subscription_onlyTheFirstPaidActivationCounts() {
        var referral = openReferral();
        referral.setSubscribedAt(LocalDateTime.now().minusMonths(1));

        service.rewardInviterForSubscription(invited); // a renewal

        verifyNoInteractions(premiumGrantService, notificationService);
    }

    @Test
    void subscription_invitedWhoAlreadyYieldedTheFirstRewardYieldsNothingMore() {
        openReferral();
        // PremiumGrantService refuses a second reward from the same invited person.
        when(premiumGrantService.grantMonth(inviter, PremiumGrantKind.REFERRAL_SUBSCRIPTION, invited.getId()))
                .thenReturn(Optional.empty());

        service.rewardInviterForSubscription(invited);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void subscription_sameHouseholdIsNotRewarded() {
        var referral = openReferral();
        invited.setHousehold(inviter.getHousehold());

        service.rewardInviterForSubscription(invited);

        assertNotNull(referral.getSubscribedAt());
        verifyNoInteractions(premiumGrantService, notificationService);
    }

    @Test
    void subscription_userWhoWasNotInvitedIsANoOp() {
        when(referralRepository.findByInvitedIdWithInviter(invited.getId())).thenReturn(Optional.empty());

        service.rewardInviterForSubscription(invited);

        verifyNoInteractions(premiumGrantService, notificationService);
    }
}
