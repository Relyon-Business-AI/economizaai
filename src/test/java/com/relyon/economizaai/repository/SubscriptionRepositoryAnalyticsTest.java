package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Subscription;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paying-vs-promo split hinges on the provider value: promo grants use
 * "manual" (or null), real payers a provider name. A promo grant must NEVER be
 * counted as paying — otherwise the dashboard shows phantom revenue.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SubscriptionRepositoryAnalyticsTest {

    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private User createUser(String suffix) {
        var household = householdRepository.save(Household.builder().inviteCode("SUB" + suffix).build());
        return userRepository.save(User.builder()
                .name("User " + suffix).email("sub" + suffix + "@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void createSubscription(String suffix, String provider, SubscriptionStatus status) {
        subscriptionRepository.save(Subscription.builder()
                .user(createUser(suffix))
                .provider(provider)
                .status(status)
                .build());
    }

    @Test
    void manualAndNullGrantsAreCountedAsPromoNotPaying() {
        createSubscription("M1", "manual", SubscriptionStatus.ACTIVE);
        createSubscription("N1", null, SubscriptionStatus.ACTIVE);
        createSubscription("P1", "stripe", SubscriptionStatus.ACTIVE);
        createSubscription("C1", "stripe", SubscriptionStatus.CANCELED);

        assertThat(subscriptionRepository.countPaying(SubscriptionStatus.ACTIVE, false)).isEqualTo(1);
        assertThat(subscriptionRepository.countPromoGranted(SubscriptionStatus.ACTIVE, false)).isEqualTo(2);
    }
}
