package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
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
    @Autowired private ReceiptRepository receiptRepository;

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

        assertThat(userRepository.countSignupsSince(since, false)).isEqualTo(4);
        assertThat(userRepository.countVerifiedSince(since, false)).isEqualTo(2);
        assertThat(userRepository.countProTierSince(since, false)).isEqualTo(1);
        // exists-subquery must execute and return 0 (no receipts created here).
        assertThat(userRepository.countActivatedSince(since, false)).isZero();

        var channelRows = userRepository.channelBreakdownSince(since, false);
        var instagramRow = channelRows.stream()
                .filter(row -> row[0] == AcquisitionChannel.INSTAGRAM_PAID)
                .findFirst().orElseThrow();
        assertThat(((Number) instagramRow[1]).longValue()).isEqualTo(2); // signups
        assertThat(((Number) instagramRow[2]).longValue()).isEqualTo(1); // verified
        assertThat(((Number) instagramRow[3]).longValue()).isEqualTo(1); // pro

        assertThat(userRepository.signupTimelineSince(since, false)).hasSize(4);
        assertThat(userRepository.campaignBreakdownSince(since, false)).isNotEmpty();
        assertThat(userRepository.tierDistribution(false)).isNotEmpty();
    }

    @Test
    void internalFilterExcludesAdminAndTestAccounts() {
        createUser("R1", AcquisitionChannel.ORGANIC, null, true, SubscriptionTier.FREE); // real user@testR1@test.com
        createInternalUser("AD", "admin1@test.com", Role.ADMIN, Platform.WEB);            // admin -> excluded
        createInternalUser("QA", "qa123@economizaai.app", Role.USER, Platform.WEB);        // test account -> excluded

        var since = LocalDateTime.now().minusDays(1);

        assertThat(userRepository.countSignupsSince(since, false)).isEqualTo(1);  // only the real user
        assertThat(userRepository.countSignupsSince(since, true)).isEqualTo(3);   // everyone
    }

    @Test
    void internalFilterExcludesFlaggedAndCloudTestLabAccounts() {
        createUser("REAL", AcquisitionChannel.ORGANIC, null, true, SubscriptionTier.FREE); // counts
        var flagged = createUser("FLAG", AcquisitionChannel.ORGANIC, null, true, SubscriptionTier.FREE);
        flagged.setExcludedFromMetrics(true);                                              // manually hidden
        userRepository.save(flagged);
        createInternalUser("GTL", "ABC-LVL-01@cloudtestlabaccounts.com", Role.USER, Platform.ANDROID); // Google robo

        var since = LocalDateTime.now().minusDays(1);

        assertThat(userRepository.countSignupsSince(since, false)).isEqualTo(1); // only the real user
        assertThat(userRepository.countSignupsSince(since, true)).isEqualTo(3);  // everyone
    }

    @Test
    void cohortActivityGroupsByAcquisitionChannelDespiteReceiptChannelCollision() {
        // Regressão: a query junta receipts (que tem coluna `channel`); agrupar pelo alias
        // `channel` fazia o Postgres resolver receipts.channel e quebrar. Deve rodar e casar
        // o usuário à sua própria semana de cadastro (offset 0).
        var user = createUser("C1", AcquisitionChannel.ORGANIC, null, true, SubscriptionTier.FREE);
        receiptRepository.save(Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso("43260412345678000190650010000123451123456780")
                .qrPayload("https://sefaz/p=chave")
                .status(ReceiptStatus.CONFIRMED)
                .build());

        var since = LocalDateTime.now().minusDays(7);
        // week_offset (date - date) é integer no Postgres mas Interval no H2 de teste; o valor
        // exato do offset é validado no dev (PG). Aqui garantimos que a query roda e agrupa certo.
        var rows = userRepository.cohortActivitySince(since, false);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(AcquisitionChannel.ORGANIC.name()); // channel
        assertThat(((Number) rows.get(0)[3]).longValue()).isEqualTo(1);          // active_users
    }

    @Test
    void platformBreakdownGroupsByRegistrationPlatform() {
        createInternalUser("P1", "p1@test.com", Role.USER, Platform.WEB);
        createInternalUser("P2", "p2@test.com", Role.USER, Platform.WEB);
        createInternalUser("P3", "p3@test.com", Role.USER, Platform.ANDROID);

        var since = LocalDateTime.now().minusDays(1);
        var rows = userRepository.platformBreakdownSince(since, false);
        var webRow = rows.stream().filter(row -> row[0] == Platform.WEB).findFirst().orElseThrow();
        assertThat(((Number) webRow[1]).longValue()).isEqualTo(2);
    }

    private User createInternalUser(String suffix, String email, Role role, Platform platform) {
        var household = householdRepository.save(Household.builder().inviteCode("IN" + suffix).build());
        return userRepository.save(User.builder()
                .name("User " + suffix).email(email).password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .role(role)
                .registrationPlatform(platform)
                .subscriptionTier(SubscriptionTier.FREE)
                .build());
    }
}
