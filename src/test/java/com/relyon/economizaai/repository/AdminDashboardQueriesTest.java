package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The date-window queries behind GET /admin/dashboard, run against the @DataJpaTest database —
 * including the native "users by predominant UF in the window" query. Rows are created "now",
 * so one window around now must see them and one in the past must not.
 */
@DataJpaTest
@ActiveProfiles("test")
class AdminDashboardQueriesTest {

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private LocalDateTime pastStart;
    private LocalDateTime pastEnd;

    @BeforeEach
    void setUp() {
        var now = LocalDateTime.now();
        windowStart = now.minusHours(1);
        windowEnd = now.plusHours(1);
        pastStart = now.minusDays(3);
        pastEnd = now.minusDays(2);

        var shopper = saveUser("shopper@test.com", "DASH01", true, AcquisitionChannel.APP_STORE);
        saveUser("newcomer@test.com", "DASH02", false, null);

        saveReceipt(shopper, UnidadeFederativa.RS, ReceiptStatus.CONFIRMED, "100.00");
        saveReceipt(shopper, UnidadeFederativa.RS, ReceiptStatus.CONFIRMED, "50.50");
        saveReceipt(shopper, UnidadeFederativa.SP, ReceiptStatus.CONFIRMED, "20.00");
        saveReceipt(shopper, UnidadeFederativa.RS, ReceiptStatus.FAILED_PARSE, "999.00");
    }

    private User saveUser(String email, String inviteCode, boolean emailVerified, AcquisitionChannel channel) {
        var household = householdRepository.save(Household.builder().inviteCode(inviteCode).build());
        return userRepository.save(User.builder()
                .name("Tester").email(email).password("x")
                .household(household)
                .emailVerified(emailVerified)
                .acquisitionChannel(channel)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void saveReceipt(User owner, UnidadeFederativa uf, ReceiptStatus status, String total) {
        receiptRepository.save(Receipt.builder()
                .user(owner).household(owner.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(uf)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 10, 30))
                .totalAmount(new BigDecimal(total))
                .qrPayload("payload").status(status)
                .build());
    }

    @Test
    void receiptAggregatesFollowTheWindow() {
        assertEquals(4, receiptRepository.countCreatedBetween(windowStart, windowEnd, false));
        assertEquals(0, receiptRepository.countCreatedBetween(pastStart, pastEnd, false));

        assertEquals(0, new BigDecimal("170.50")
                .compareTo(receiptRepository.sumConfirmedTotalCreatedBetween(windowStart, windowEnd, false)));
        assertEquals(0, BigDecimal.ZERO
                .compareTo(receiptRepository.sumConfirmedTotalCreatedBetween(pastStart, pastEnd, false)));

        assertEquals(1, receiptRepository.countActiveHouseholdsBetween(windowStart, windowEnd, false));
        assertEquals(0, receiptRepository.countActiveHouseholdsBetween(pastStart, pastEnd, false));

        Map<ReceiptStatus, Long> byStatus = receiptRepository.statusBreakdownBetween(windowStart, windowEnd, false).stream()
                .collect(Collectors.toMap(row -> (ReceiptStatus) row[0], row -> ((Number) row[1]).longValue()));
        assertEquals(3L, byStatus.get(ReceiptStatus.CONFIRMED));
        assertEquals(1L, byStatus.get(ReceiptStatus.FAILED_PARSE));
    }

    @Test
    void stateCutCountsConfirmedReceiptsAndPredominantUfInTheWindow() {
        var receiptsByUf = receiptRepository.confirmedReceiptsByUfBetween(windowStart, windowEnd, false);
        assertEquals(2, receiptsByUf.size());
        assertEquals("RS", receiptsByUf.get(0)[0].toString());
        assertEquals(2L, ((Number) receiptsByUf.get(0)[1]).longValue());
        assertEquals(0, new BigDecimal("150.50").compareTo((BigDecimal) receiptsByUf.get(0)[2]));

        var usersByUf = receiptRepository.usersByPredominantUfBetween(windowStart, windowEnd, false);
        assertEquals(1, usersByUf.size());
        assertEquals("RS", usersByUf.get(0)[0].toString());
        assertEquals(1L, ((Number) usersByUf.get(0)[1]).longValue());

        assertTrue(receiptRepository.confirmedReceiptsByUfBetween(pastStart, pastEnd, false).isEmpty());
        assertTrue(receiptRepository.usersByPredominantUfBetween(pastStart, pastEnd, false).isEmpty());
    }

    @Test
    void signupFunnelAndTimelineFollowTheWindow() {
        assertEquals(2, userRepository.countSignupsBetween(windowStart, windowEnd, false));
        assertEquals(1, userRepository.countVerifiedBetween(windowStart, windowEnd, false));
        assertEquals(1, userRepository.countActivatedBetween(windowStart, windowEnd, false));
        assertEquals(0, userRepository.countSignupsBetween(pastStart, pastEnd, false));

        var timeline = userRepository.signupTimelineBetween(windowStart, windowEnd, false);
        assertEquals(2, timeline.size());
        assertTrue(timeline.stream().anyMatch(row -> row[1] == AcquisitionChannel.APP_STORE));
        assertTrue(userRepository.signupTimelineBetween(pastStart, pastEnd, false).isEmpty());

        var earliest = userRepository.findEarliestCreatedAt();
        assertTrue(earliest.isPresent());
        assertTrue(!earliest.get().isBefore(windowStart) && earliest.get().isBefore(windowEnd));
    }
}
