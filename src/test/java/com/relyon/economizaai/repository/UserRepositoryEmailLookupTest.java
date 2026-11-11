package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/** E-mail lookups match the stored (normalized) address whatever casing the caller typed. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryEmailLookupTest {

    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;

    @Test
    void findsAndDetectsAccountRegardlessOfTypedCasing() {
        var household = householdRepository.save(Household.builder().inviteCode("EMLCASE").build());
        var saved = userRepository.save(User.builder()
                .name("Maria").email("maria.lookup@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());

        assertThat(userRepository.findByEmail("  Maria.Lookup@Economizaai.APP "))
                .map(User::getId).contains(saved.getId());
        assertThat(userRepository.existsByEmail("MARIA.LOOKUP@economizaai.app")).isTrue();
        assertThat(userRepository.existsByEmail("other@economizaai.app")).isFalse();
        assertThat(userRepository.findByEmail(null)).isEmpty();
    }
}
