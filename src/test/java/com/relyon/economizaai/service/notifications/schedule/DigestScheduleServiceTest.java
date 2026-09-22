package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DigestScheduleServiceTest {

    @Mock private ReceiptRepository receiptRepository;
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
    void defaultsTo16WhenFewerThanFiveReceipts() {
        var user = user(null);
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of(hourCount(18, 4)));

        assertEquals(16, service.effectiveSendHour(user));
    }

    @Test
    void defaultsTo16WhenNoHistory() {
        var user = user(null);
        when(receiptRepository.findConfirmedIssuedHourHistogram(HOUSEHOLD_ID))
                .thenReturn(List.of());

        assertEquals(16, service.effectiveSendHour(user));
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
