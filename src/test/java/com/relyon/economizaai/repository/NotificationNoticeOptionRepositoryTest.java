package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/** The notice option rows and the weekly report send list that reads them. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NotificationNoticeOptionRepositoryTest {

    @Autowired private NotificationNoticeOptionRepository optionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;

    private User savedUser(String localPart, boolean whatsAppWeeklyReportEnabled) {
        var household = householdRepository.save(Household.builder().inviteCode(localPart.toUpperCase()).build());
        return userRepository.save(User.builder()
                .name(localPart).email(localPart + "@economizaai.app").password("x")
                .household(household)
                .whatsAppWeeklyReportEnabled(whatsAppWeeklyReportEnabled)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void savedOption(User user, NotificationNotice notice, NotificationChannel channel, boolean enabled) {
        optionRepository.save(NotificationNoticeOption.builder()
                .user(user).notice(notice).channel(channel).enabled(enabled).build());
    }

    @Test
    void findsTheOptionsOfOneUserAndOfOneNotice() {
        var user = savedUser("opt1", false);
        var otherUser = savedUser("opt2", false);
        savedOption(user, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);
        savedOption(user, NotificationNotice.STOCKOUT, NotificationChannel.PUSH, false);
        savedOption(otherUser, NotificationNotice.PRICE_ALERTS, NotificationChannel.PUSH, false);

        assertThat(optionRepository.findAllByUserId(user.getId())).hasSize(2);
        assertThat(optionRepository.findAllByUserIdAndNotice(user.getId(), NotificationNotice.PRICE_ALERTS))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.getChannel()).isEqualTo(NotificationChannel.EMAIL);
                    assertThat(option.isEnabled()).isTrue();
                });
    }

    @Test
    void weeklyReportCandidatesHaveTheEmailOptionOnOrTheWhatsAppToggleOn() {
        var byEmail = savedUser("wr1", false);
        savedOption(byEmail, NotificationNotice.WEEKLY_REPORT, NotificationChannel.EMAIL, true);
        var byWhatsApp = savedUser("wr2", true);
        var emailSwitchedOff = savedUser("wr3", false);
        savedOption(emailSwitchedOff, NotificationNotice.WEEKLY_REPORT, NotificationChannel.EMAIL, false);
        var otherNoticeByEmail = savedUser("wr4", false);
        savedOption(otherNoticeByEmail, NotificationNotice.PRICE_ALERTS, NotificationChannel.EMAIL, true);

        assertThat(userRepository.findWeeklyReportCandidates())
                .extracting(User::getId)
                .contains(byEmail.getId(), byWhatsApp.getId())
                .doesNotContain(emailSwitchedOff.getId(), otherNoticeByEmail.getId());
    }
}
