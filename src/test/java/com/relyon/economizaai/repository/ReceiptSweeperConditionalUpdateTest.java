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

import java.time.LocalDateTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweeper's two conditional updates against a real schema: a row is only touched when it
 * is STILL in the stale status and still older than the cutoff, and the re-queue happens once.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptSweeperConditionalUpdateTest {

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        var household = householdRepository.save(Household.builder().inviteCode("SWEEP1").build());
        user = userRepository.save(User.builder()
                .name("Tester").email("test48213@economizaai.app").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Receipt saved(ReceiptStatus status) {
        return receiptRepository.saveAndFlush(Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .qrPayload("https://sefaz/qr?p=abc")
                .origin(ReceiptOrigin.SCAN)
                .status(status)
                .build());
    }

    private static LocalDateTime cutoffAfterNow() {
        return LocalDateTime.now().plusMinutes(1);
    }

    @Test
    void failIfStillStale_failsARowStillInTheStaleStatus() {
        var stuck = saved(ReceiptStatus.PROCESSING);

        var updatedRows = receiptRepository.failIfStillStale(stuck.getId(), ReceiptStatus.PROCESSING,
                cutoffAfterNow(), ReceiptStatus.FAILED_PARSE, "receipt.processing.timeout", LocalDateTime.now());

        var reloaded = receiptRepository.findById(stuck.getId()).orElseThrow();
        assertEquals(1, updatedRows);
        assertEquals(ReceiptStatus.FAILED_PARSE, reloaded.getStatus());
        assertEquals("receipt.processing.timeout", reloaded.getParseErrorReason());
    }

    @Test
    void failIfStillStale_leavesAReceiptThatAlreadyFinishedUntouched() {
        var finished = saved(ReceiptStatus.PENDING_CONFIRMATION);

        var updatedRows = receiptRepository.failIfStillStale(finished.getId(), ReceiptStatus.PROCESSING,
                cutoffAfterNow(), ReceiptStatus.FAILED_PARSE, "receipt.processing.timeout", LocalDateTime.now());

        var reloaded = receiptRepository.findById(finished.getId()).orElseThrow();
        assertEquals(0, updatedRows);
        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, reloaded.getStatus());
    }

    @Test
    void failIfStillStale_leavesARowTouchedAfterTheCutoffUntouched() {
        var freshlyRetried = saved(ReceiptStatus.PROCESSING);

        var updatedRows = receiptRepository.failIfStillStale(freshlyRetried.getId(), ReceiptStatus.PROCESSING,
                LocalDateTime.now().minusMinutes(10), ReceiptStatus.FAILED_PARSE, "receipt.processing.timeout",
                LocalDateTime.now());

        assertEquals(0, updatedRows);
        assertEquals(ReceiptStatus.PROCESSING,
                receiptRepository.findById(freshlyRetried.getId()).orElseThrow().getStatus());
    }

    @Test
    void requeueStrandedOnce_requeuesTheFirstTimeAndRefusesTheSecond() {
        var stranded = saved(ReceiptStatus.PROCESSING);

        var firstAttempt = receiptRepository.requeueStrandedOnce(stranded.getId(), ReceiptStatus.PROCESSING,
                cutoffAfterNow(), ReceiptStatus.IMPORT_QUEUED, false, LocalDateTime.now());
        var requeued = receiptRepository.findById(stranded.getId()).orElseThrow();
        assertEquals(1, firstAttempt);
        assertEquals(ReceiptStatus.IMPORT_QUEUED, requeued.getStatus());
        assertTrue(requeued.isStrandedRequeued());
        assertFalse(requeued.isResyncFreeOnly());

        // Stranded again later: the second chance was already used.
        requeued.setStatus(ReceiptStatus.PROCESSING);
        receiptRepository.saveAndFlush(requeued);
        var secondAttempt = receiptRepository.requeueStrandedOnce(stranded.getId(), ReceiptStatus.PROCESSING,
                cutoffAfterNow(), ReceiptStatus.IMPORT_QUEUED, false, LocalDateTime.now());

        assertEquals(0, secondAttempt);
        assertEquals(ReceiptStatus.PROCESSING, receiptRepository.findById(stranded.getId()).orElseThrow().getStatus());
    }
}
