package com.relyon.economizaai.service.profile;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoreReviewPromptServiceTest {

    private static final int MIN_CONFIRMED = 2;
    private static final int LOOKBACK_DAYS = 7;
    private static final int MAX_PROMPTS = 3;
    private static final int COOLDOWN_DAYS = 90;

    @Mock private ReceiptRepository receiptRepository;
    @Mock private UserRepository userRepository;

    private StoreReviewPromptService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new StoreReviewPromptService(receiptRepository, userRepository,
                MIN_CONFIRMED, LOOKBACK_DAYS, MAX_PROMPTS, COOLDOWN_DAYS);
        user = User.builder().id(UUID.randomUUID()).email("test48213@economizaai.app").build();
    }

    @Test
    void eligibleOnSecondConfirmedReceiptWithoutRecentFailures() {
        when(receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED)).thenReturn(2L);
        when(receiptRepository.countByUserIdAndStatusInAndUpdatedAtGreaterThanEqual(
                eq(user.getId()), anyCollection(), any(LocalDateTime.class))).thenReturn(0L);

        var response = service.evaluate(user);

        assertTrue(response.eligible());
        assertEquals("ok", response.reason());
    }

    @Test
    void blockedWhenOnlyOneConfirmedReceipt() {
        when(receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED)).thenReturn(1L);

        var response = service.evaluate(user);

        assertFalse(response.eligible());
        assertEquals(StoreReviewPromptService.REASON_NOT_ENOUGH_RECEIPTS, response.reason());
        verify(receiptRepository, never()).countByUserIdAndStatusInAndUpdatedAtGreaterThanEqual(
                any(), anyCollection(), any());
    }

    @Test
    void blockedWhenAReceiptFailedRecently() {
        when(receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED)).thenReturn(5L);
        when(receiptRepository.countByUserIdAndStatusInAndUpdatedAtGreaterThanEqual(
                eq(user.getId()), anyCollection(), any(LocalDateTime.class))).thenReturn(1L);

        var response = service.evaluate(user);

        assertFalse(response.eligible());
        assertEquals(StoreReviewPromptService.REASON_RECENT_FAILURE, response.reason());
    }

    @Test
    void recentFailureWindowCoversEveryFailureStatus() {
        when(receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED)).thenReturn(5L);
        when(receiptRepository.countByUserIdAndStatusInAndUpdatedAtGreaterThanEqual(
                eq(user.getId()), anyCollection(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    Collection<ReceiptStatus> statuses = invocation.getArgument(1);
                    assertTrue(statuses.contains(ReceiptStatus.FAILED_PARSE));
                    assertTrue(statuses.contains(ReceiptStatus.CONTINGENCY_PENDING));
                    assertTrue(statuses.contains(ReceiptStatus.NEEDS_DEVICE_FETCH));
                    assertFalse(statuses.contains(ReceiptStatus.REJECTED));
                    return 0L;
                });

        assertTrue(service.evaluate(user).eligible());
    }

    @Test
    void blockedAfterMaxPromptsWithoutTouchingReceipts() {
        user.setStoreReviewPromptCount(MAX_PROMPTS);

        var response = service.evaluate(user);

        assertFalse(response.eligible());
        assertEquals(StoreReviewPromptService.REASON_MAX_PROMPTS, response.reason());
        verify(receiptRepository, never()).countByUserIdAndStatus(any(), any());
    }

    @Test
    void blockedDuringCooldownAfterLastPrompt() {
        user.setStoreReviewPromptCount(1);
        user.setStoreReviewLastPromptedAt(LocalDateTime.now().minusDays(COOLDOWN_DAYS - 1));

        var response = service.evaluate(user);

        assertFalse(response.eligible());
        assertEquals(StoreReviewPromptService.REASON_COOLDOWN, response.reason());
    }

    @Test
    void eligibleAgainOnceCooldownElapsed() {
        user.setStoreReviewPromptCount(1);
        user.setStoreReviewLastPromptedAt(LocalDateTime.now().minusDays(COOLDOWN_DAYS + 1));
        when(receiptRepository.countByUserIdAndStatus(user.getId(), ReceiptStatus.CONFIRMED)).thenReturn(10L);
        when(receiptRepository.countByUserIdAndStatusInAndUpdatedAtGreaterThanEqual(
                eq(user.getId()), anyCollection(), any(LocalDateTime.class))).thenReturn(0L);

        assertTrue(service.evaluate(user).eligible());
    }

    @Test
    void recordPromptedIncrementsCountAndStampsTime() {
        user.setStoreReviewPromptCount(1);

        service.recordPrompted(user);

        assertEquals(2, user.getStoreReviewPromptCount());
        assertNotNull(user.getStoreReviewLastPromptedAt());
        verify(userRepository).save(user);
    }
}
