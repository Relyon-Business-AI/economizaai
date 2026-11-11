package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL side of the notification batch jobs: who is due, in pages, and the
 * atomic claims that keep two runs from sending the same thing.
 */
@DataJpaTest
@ActiveProfiles("test")
class NotificationBatchSelectionTest {

    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);
    private static final OffsetDateTime START_OF_TODAY =
            OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.ofHours(-3));
    private static final PageRequest FIRST_HUNDRED = PageRequest.of(0, 100);

    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationRuleRepository ruleRepository;
    @Autowired private EntityManager entityManager;

    private int sequence = 0;

    private User.UserBuilder<?, ?> userBuilder() {
        sequence++;
        var household = householdRepository.save(Household.builder().inviteCode("BATCH" + sequence).build());
        return User.builder()
                .name("Batch " + sequence).email("batch" + sequence + "@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0));
    }

    private User digestUser(Integer effectiveHour, DigestFrequency frequency, OffsetDateTime lastSent) {
        return userRepository.save(userBuilder()
                .digestEffectiveSendHour(effectiveHour).digestFrequency(frequency).lastDigestSentAt(lastSent)
                .build());
    }

    private List<UUID> dueIds(int currentHour, boolean weeklyDay) {
        return userRepository.findDigestDuePage(currentHour, weeklyDay, START_OF_TODAY, BEFORE_FIRST_ID, FIRST_HUNDRED)
                .stream().map(User::getId).toList();
    }

    // ---------- deals digest: due selection ----------

    @Test
    void digestDue_whenTheSendHourWasReachedAndNothingWentOutToday() {
        var exactlyNow = digestUser(16, DigestFrequency.DAILY, null);
        var earlierToday = digestUser(11, DigestFrequency.DAILY, START_OF_TODAY.minusHours(20));
        var laterToday = digestUser(19, DigestFrequency.DAILY, null);
        var alreadySentToday = digestUser(11, DigestFrequency.DAILY, START_OF_TODAY.plusHours(11));
        var withoutHour = digestUser(null, DigestFrequency.DAILY, null);

        var due = dueIds(16, false);

        // An hour that already passed is still due (a late run does not skip the user).
        assertThat(due).containsExactlyInAnyOrder(exactlyNow.getId(), earlierToday.getId());
        assertThat(due).doesNotContain(laterToday.getId(), alreadySentToday.getId(), withoutHour.getId());
    }

    @Test
    void digestDue_skipsOffAndInactiveUsers_andWeeklyOnlyOnTheWeeklyDay() {
        var off = digestUser(10, DigestFrequency.OFF, null);
        var weekly = digestUser(10, DigestFrequency.WEEKLY, null);
        var inactive = userRepository.save(userBuilder()
                .digestEffectiveSendHour(10).active(false).build());

        assertThat(dueIds(16, false)).doesNotContain(off.getId(), weekly.getId(), inactive.getId());
        assertThat(dueIds(16, true)).contains(weekly.getId()).doesNotContain(off.getId(), inactive.getId());
    }

    @Test
    void digestDue_pagesByIdWithoutSkippingOrRepeating() {
        var expected = new ArrayList<UUID>();
        for (var index = 0; index < 5; index++) {
            expected.add(digestUser(9, DigestFrequency.DAILY, null).getId());
        }

        var seen = new ArrayList<UUID>();
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var page = userRepository.findDigestDuePage(16, false, START_OF_TODAY, afterId, PageRequest.of(0, 2));
            if (page.isEmpty()) break;
            assertThat(page.size()).isLessThanOrEqualTo(2);
            page.forEach(user -> seen.add(user.getId()));
            afterId = page.get(page.size() - 1).getId();
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void claimDigestSlot_onlyTheFirstClaimOfTheDayWins() {
        var user = digestUser(9, DigestFrequency.DAILY, START_OF_TODAY.minusDays(1));
        var sentAt = START_OF_TODAY.plusHours(9);

        assertThat(userRepository.claimDigestSlot(user.getId(), sentAt, START_OF_TODAY)).isEqualTo(1);
        assertThat(userRepository.claimDigestSlot(user.getId(), sentAt.plusMinutes(1), START_OF_TODAY)).isZero();
        entityManager.clear();
        assertThat(dueIds(16, false)).doesNotContain(user.getId());
    }

    @Test
    void usersWithoutSendHour_areListedUntilOneIsAssigned() {
        var newUser = digestUser(null, DigestFrequency.DAILY, null);
        var assigned = digestUser(14, DigestFrequency.DAILY, null);
        var off = digestUser(null, DigestFrequency.OFF, null);

        var missing = userRepository.findDigestUsersWithoutSendHour(BEFORE_FIRST_ID, FIRST_HUNDRED)
                .stream().map(User::getId).toList();
        assertThat(missing).contains(newUser.getId()).doesNotContain(assigned.getId(), off.getId());

        userRepository.updateDigestEffectiveSendHour(newUser.getId(), 12);
        entityManager.clear();

        assertThat(userRepository.findDigestUsersWithoutSendHour(BEFORE_FIRST_ID, FIRST_HUNDRED))
                .extracting(User::getId).doesNotContain(newUser.getId());
        assertThat(userRepository.findById(newUser.getId()).orElseThrow().getDigestEffectiveSendHour()).isEqualTo(12);
    }

    // ---------- push opt-in nudge ----------

    @Test
    void pushNudgeCandidates_haveNoTokenAndNoRecentInvite() {
        var neverInvited = userRepository.save(userBuilder().build());
        var invitedRecently = userRepository.save(userBuilder().build());
        var hasToken = userRepository.save(userBuilder().pushDeviceToken("ExponentPushToken[x]").build());
        notificationRepository.save(Notification.builder().user(invitedRecently)
                .type(NotificationType.PUSH_OPTIN).channel(NotificationChannel.INBOX).title("t").body("b").build());
        // Another kind of notification does not count as an invite.
        notificationRepository.save(Notification.builder().user(neverInvited)
                .type(NotificationType.SYSTEM).channel(NotificationChannel.INBOX).title("t").body("b").build());
        entityManager.flush();

        var candidates = userRepository.findPushOptInNudgeCandidates(
                        LocalDateTime.now().minusDays(30), BEFORE_FIRST_ID, FIRST_HUNDRED)
                .stream().map(User::getId).toList();

        assertThat(candidates).contains(neverInvited.getId())
                .doesNotContain(invitedRecently.getId(), hasToken.getId());
    }

    @Test
    void pushNudgeCandidates_anOldInviteMakesTheUserEligibleAgain() {
        var invitedLongAgo = userRepository.save(userBuilder().build());
        notificationRepository.save(Notification.builder().user(invitedLongAgo)
                .type(NotificationType.PUSH_OPTIN).channel(NotificationChannel.INBOX).title("t").body("b").build());
        entityManager.flush();

        // "Recent" starts in the future here, so the invite just saved counts as old.
        var candidates = userRepository.findPushOptInNudgeCandidates(
                LocalDateTime.now().plusDays(1), BEFORE_FIRST_ID, FIRST_HUNDRED);

        assertThat(candidates).extracting(User::getId).contains(invitedLongAgo.getId());
    }

    // ---------- quiet hours: held-back notifications ----------

    @Test
    void heldBackNotifications_areListedPerUserAndClaimedOnce() {
        var user = userRepository.save(userBuilder().build());
        var first = notificationRepository.save(Notification.builder().user(user)
                .type(NotificationType.PRICE_DROP).channel(NotificationChannel.PUSH)
                .title("first").body("b").dispatchDeferred(true).build());
        var second = notificationRepository.save(Notification.builder().user(user)
                .type(NotificationType.BUDGET).channel(NotificationChannel.PUSH)
                .title("second").body("b").dispatchDeferred(true).build());
        notificationRepository.save(Notification.builder().user(user)
                .type(NotificationType.SYSTEM).channel(NotificationChannel.PUSH)
                .title("sent at once").body("b").build());
        entityManager.flush();

        assertThat(notificationRepository.findUserIdsWithDeferredDispatch(FIRST_HUNDRED)).containsExactly(user.getId());
        assertThat(notificationRepository.findDeferredByUserId(user.getId()))
                .extracting(Notification::getId).containsExactlyInAnyOrder(first.getId(), second.getId());

        assertThat(notificationRepository.claimDeferredDispatch(first.getId())).isEqualTo(1);
        assertThat(notificationRepository.claimDeferredDispatch(first.getId())).isZero();
        assertThat(notificationRepository.claimDeferredDispatch(second.getId())).isEqualTo(1);
        entityManager.clear();

        assertThat(notificationRepository.findUserIdsWithDeferredDispatch(FIRST_HUNDRED)).isEmpty();
    }

    // ---------- spend digests: rule paging ----------

    @Test
    void activeRulesByType_pageByIdAndSkipInactiveOrOtherTypes() {
        var expected = new ArrayList<UUID>();
        for (var index = 0; index < 3; index++) {
            var user = userRepository.save(userBuilder().build());
            expected.add(ruleRepository.save(NotificationRule.builder().user(user)
                    .type(NotificationType.DIGEST).isDefault(true).active(true).build()).getId());
        }
        var muted = userRepository.save(userBuilder().build());
        ruleRepository.save(NotificationRule.builder().user(muted)
                .type(NotificationType.DIGEST).isDefault(true).active(false).build());
        ruleRepository.save(NotificationRule.builder().user(muted)
                .type(NotificationType.PROMO_PERSONAL).isDefault(true).active(true).build());

        var seen = new ArrayList<UUID>();
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var page = ruleRepository.findActivePageByTypeFetchUser(
                    NotificationType.DIGEST, afterId, PageRequest.of(0, 2));
            if (page.isEmpty()) break;
            page.forEach(rule -> seen.add(rule.getId()));
            afterId = page.get(page.size() - 1).getId();
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }
}
