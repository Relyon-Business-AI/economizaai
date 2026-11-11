package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestScheduleServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private DigestScheduleService service;

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();

    private User user(Integer sendHourOverride) {
        return User.builder().id(UUID.randomUUID()).email("u@example.com")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .digestSendHour(sendHourOverride)
                .build();
    }

    private ReceiptRepository.HourCount hourCount(int hour, long count) {
        return new ReceiptRepository.HourCount() {
            @Override public int getHourOfDay() { return hour; }
            @Override public long getReceiptCount() { return count; }
        };
    }

    @Test
    void explicitOverrideWins() {
        var user = user(9);
        // History present but irrelevant — override short-circuits.
        lenient().when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of(hourCount(20, 10)));

        assertEquals(9, service.effectiveSendHour(user));
    }

    @Test
    void modalHourMinusOneWhenEnoughReceipts() {
        var user = user(null);
        // 6 receipts, modal hour 18 -> send at 17.
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of(hourCount(10, 1), hourCount(18, 5)));

        assertEquals(17, service.effectiveSendHour(user));
    }

    @Test
    void fallsBackToThePerUserDefaultWhenFewerThanFiveReceipts() {
        var user = user(null);
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of(hourCount(18, 4)));

        assertEquals(DigestScheduleService.spreadDefaultHour(user.getId()), service.effectiveSendHour(user));
    }

    @Test
    void fallsBackToThePerUserDefaultWhenNoHistory() {
        var user = user(null);
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of());

        assertEquals(DigestScheduleService.spreadDefaultHour(user.getId()), service.effectiveSendHour(user));
    }

    @Test
    void defaultHourIsStablePerUserAndSpreadBetween11And19() {
        var hoursSeen = new HashSet<Integer>();
        for (var sample = 0; sample < 500; sample++) {
            var userId = UUID.randomUUID();
            var hour = DigestScheduleService.spreadDefaultHour(userId);
            assertTrue(hour >= 11 && hour <= 19, "hour " + hour);
            assertEquals(hour, DigestScheduleService.spreadDefaultHour(userId), "same user, same hour");
            hoursSeen.add(hour);
        }
        // Not everyone at the same hour: the whole window is used.
        assertEquals(9, hoursSeen.size());
    }

    @Test
    void assignMissingSendHours_storesTheResolvedHourWithATargetedUpdate() {
        var newUser = user(null);
        var withOverride = user(9);
        when(userRepository.findDigestUsersWithoutSendHour(any(), any()))
                .thenReturn(List.of(newUser, withOverride)).thenReturn(List.of());
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID)).thenReturn(List.of());

        var assigned = service.assignMissingSendHours();

        assertEquals(2, assigned);
        verify(userRepository).updateDigestEffectiveSendHour(newUser.getId(),
                DigestScheduleService.spreadDefaultHour(newUser.getId()));
        verify(userRepository).updateDigestEffectiveSendHour(withOverride.getId(), 9);
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignMissingSendHours_oneFailureDoesNotStopTheOthers() {
        var failing = user(null);
        var healthy = user(null);
        when(userRepository.findDigestUsersWithoutSendHour(any(), any()))
                .thenReturn(List.of(failing, healthy)).thenReturn(List.of());
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID)).thenReturn(List.of());
        when(userRepository.updateDigestEffectiveSendHour(eq(failing.getId()), anyInt()))
                .thenThrow(new IllegalStateException("db hiccup"));

        assertEquals(1, service.assignMissingSendHours());

        verify(userRepository).updateDigestEffectiveSendHour(eq(healthy.getId()), anyInt());
    }

    @Test
    void updateSendHourFromShoppingHabits_adoptsTheInferredHourOnceThereIsHistory() {
        var user = user(null);
        user.setDigestEffectiveSendHour(13);
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of(hourCount(18, 6)));

        service.updateSendHourFromShoppingHabits(user);

        verify(userRepository).updateDigestEffectiveSendHour(user.getId(), 17);
    }

    @Test
    void updateSendHourFromShoppingHabits_keepsTheStoredHourWithoutHistoryOrWithAnOverride() {
        var withoutHistory = user(null);
        withoutHistory.setDigestEffectiveSendHour(13);
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID)).thenReturn(List.of());

        service.updateSendHourFromShoppingHabits(withoutHistory);
        service.updateSendHourFromShoppingHabits(user(9));

        verify(userRepository, never()).updateDigestEffectiveSendHour(any(), anyInt());
    }

    @Test
    void modalHourZeroWrapsToTwentyThree() {
        var user = user(null);
        // Modal hour 0 (midnight shopping) minus 1 wraps to 23.
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of(hourCount(0, 5), hourCount(14, 1)));

        assertEquals(23, service.effectiveSendHour(user));
    }
}
