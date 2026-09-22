package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the native hour-of-day histogram the digest scheduler uses to infer
 * a household's typical shopping hour. Exercises EXTRACT(HOUR ...) on H2 in
 * PostgreSQL mode (the @DataJpaTest backend).
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptHourHistogramTest {

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private UUID householdId;
    private User user;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("HIST01").build());
        householdId = household.getId();
        user = userRepository.save(User.builder()
                .name("Tester").email("hist@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void saveConfirmedAtHour(int hour) {
        receiptRepository.save(Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, hour, 30))
                .totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build());
    }

    @Test
    void groupsConfirmedReceiptsByIssuedHour() {
        saveConfirmedAtHour(18);
        saveConfirmedAtHour(18);
        saveConfirmedAtHour(18);
        saveConfirmedAtHour(9);
        // A null-issued receipt is excluded from the histogram.
        receiptRepository.save(Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .issuedAt(null).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build());

        var histogram = receiptRepository.findConfirmedIssuedHourHistogram(householdId);

        var totalCounted = histogram.stream().mapToLong(ReceiptRepository.HourCount::getReceiptCount).sum();
        assertEquals(4, totalCounted, "null-issued receipt is excluded");
        var modal = histogram.stream()
                .max((left, right) -> Long.compare(left.getReceiptCount(), right.getReceiptCount()))
                .orElseThrow();
        assertEquals(18, modal.getHourOfDay());
        assertEquals(3, modal.getReceiptCount());
    }
}
