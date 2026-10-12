package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the admin bulk-retry-by-reason lookup: a case-insensitive CONTAINS on
 * parseErrorReason (which is "key:args") matches the Infosimples failures (arg or
 * key), scoped to FAILED_PARSE only.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptFailureReasonQueryTest {

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("FAIL01").build());
        user = userRepository.save(User.builder()
                .name("Tester").email("fail@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void saveFailed(String reason) {
        receiptRepository.save(Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .totalAmount(new BigDecimal("10.00")).qrPayload("payload")
                .origin(ReceiptOrigin.SCAN)
                .status(ReceiptStatus.FAILED_PARSE).parseErrorReason(reason)
                .build());
    }

    @Test
    void containsMatchesInfosimplesFailuresCaseInsensitive() {
        saveFailed("receipt.paid_api.quota_exceeded:INFOSIMPLES");
        saveFailed("receipt.parse.failed:infosimples.error");
        saveFailed("receipt.state.app_update_required");
        saveFailed("receipt.parse.failed:no-items-found");
        // CONFIRMED with an infosimples-ish reason must NOT match (status-scoped).
        receiptRepository.save(Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .totalAmount(new BigDecimal("10.00")).qrPayload("payload")
                .origin(ReceiptOrigin.SCAN)
                .status(ReceiptStatus.CONFIRMED).parseErrorReason("something:INFOSIMPLES")
                .build());

        var infosimples = receiptRepository
                .findByStatusAndParseErrorReasonContainingIgnoreCase(ReceiptStatus.FAILED_PARSE, "infosimples");
        assertEquals(2, infosimples.size(), "both INFOSIMPLES + infosimples.error FAILED notas, not the CONFIRMED one");
        assertTrue(infosimples.stream().allMatch(r -> r.getStatus() == ReceiptStatus.FAILED_PARSE));

        var appUpdate = receiptRepository
                .findByStatusAndParseErrorReasonContainingIgnoreCase(ReceiptStatus.FAILED_PARSE, "app_update");
        assertEquals(1, appUpdate.size());
    }
}
