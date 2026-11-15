package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.SpendingReportDeliveryRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class SpendingReportDeliveryLedgerTest {

    private static final LocalDate OCTOBER_FIRST = LocalDate.of(2026, Month.OCTOBER, 1);

    @Autowired private SpendingReportDeliveryRepository deliveryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseholdRepository householdRepository;

    private SpendingReportDeliveryLedger ledger;
    private User user;

    @BeforeEach
    void setUp() {
        ledger = new SpendingReportDeliveryLedger(deliveryRepository);
        var household = householdRepository.save(Household.builder().inviteCode("LEDGER1").build());
        user = userRepository.save(User.builder()
                .name("Ledger").email("ledger84213@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    @Test
    void theSamePeriodCanBeClaimedForOtherFrequenciesAndPeriods() {
        assertTrue(ledger.claim(user, SpendingReportFrequency.MONTHLY, OCTOBER_FIRST).isPresent());
        assertTrue(ledger.claim(user, SpendingReportFrequency.WEEKLY, OCTOBER_FIRST).isPresent());
        assertTrue(ledger.claim(user, SpendingReportFrequency.MONTHLY, OCTOBER_FIRST.plusMonths(1)).isPresent());

        assertEquals(3, deliveryRepository.count());
    }

    @Test
    void aReleasedClaimCanBeClaimedAgain() {
        var claimedDelivery = ledger.claim(user, SpendingReportFrequency.MONTHLY, OCTOBER_FIRST).orElseThrow();

        ledger.release(claimedDelivery);
        deliveryRepository.flush();

        assertTrue(ledger.claim(user, SpendingReportFrequency.MONTHLY, OCTOBER_FIRST).isPresent());
    }

    @Test
    void theSamePeriodIsClaimedOnlyOnce() {
        assertTrue(ledger.claim(user, SpendingReportFrequency.MONTHLY, OCTOBER_FIRST).isPresent());

        assertTrue(ledger.claim(user, SpendingReportFrequency.MONTHLY, OCTOBER_FIRST).isEmpty());
    }
}
