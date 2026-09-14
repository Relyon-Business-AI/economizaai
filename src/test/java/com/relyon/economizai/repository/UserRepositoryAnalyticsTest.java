package com.relyon.economizai.repository;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AcquisitionChannel;
import com.relyon.economizai.model.enums.SubscriptionTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the acquisition aggregation queries against the real (Flyway-migrated)
 * test DB — the Object[] projections, {@code group by} on a nullable enum, the
 * {@code sum(case when ...)} counters and the {@code exists} subquery.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryAnalyticsTest {

    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;

    private User createUser(String suffix, AcquisitionChannel channel, String campaign,
                            boolean verified, SubscriptionTier tier) {
        var household = householdRepository.save(Household.builder().inviteCode("INV" + suffix).build());
        return userRepository.save(User.builder()
                .name("User " + suffix).email("user" + suffix + "@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .acquisitionChannel(channel)
                .utmSource(channel == AcquisitionChannel.INSTAGRAM_PAID ? "instagram" : null)
                .utmMedium(channel == AcquisitionChannel.INSTAGRAM_PAID ? "paid" : null)
                .utmCampaign(campaign)
                .emailVerified(verified)
                .subscriptionTier(tier)
                .build());
    }

    @Test
    void aggregatesSignupsByChannelTierAndFunnel() {
        createUser("A1", AcquisitionChannel.INSTAGRAM_PAID, "setembro", true, SubscriptionTier.PRO);
        createUser("A2", AcquisitionChannel.INSTAGRAM_PAID, "setembro", false, SubscriptionTier.FREE);
        createUser("A3", AcquisitionChannel.ORGANIC, null, true, SubscriptionTier.FREE);
        createUser("A4", null, null, false, SubscriptionTier.FREE);

        var since = LocalDateTime.now().minusDays(1);

        assertThat(userRepository.countByCreatedAtGreaterThanEqual(since)).isEqualTo(4);
        assertThat(userRepository.countVerifiedSince(since)).isEqualTo(2);
        assertThat(userRepository.countProTierSince(since)).isEqualTo(1);
        // exists-subquery must execute and return 0 (no receipts created here).
        assertThat(userRepository.countActivatedSince(since)).isZero();

        var channelRows = userRepository.channelBreakdownSince(since);
        var instagramRow = channelRows.stream()
                .filter(row -> row[0] == AcquisitionChannel.INSTAGRAM_PAID)
                .findFirst().orElseThrow();
        assertThat(((Number) instagramRow[1]).longValue()).isEqualTo(2); // signups
        assertThat(((Number) instagramRow[2]).longValue()).isEqualTo(1); // verified
        assertThat(((Number) instagramRow[3]).longValue()).isEqualTo(1); // pro

        assertThat(userRepository.signupTimelineSince(since)).hasSize(4);
        assertThat(userRepository.campaignBreakdownSince(since)).isNotEmpty();
        assertThat(userRepository.tierDistribution()).isNotEmpty();
    }
}
