package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiUsageLog;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGatewayTest {

    @Mock private AnthropicChatAdapter chatAdapter;
    @Mock private AiUsageLogRepository usageRepository;

    private AiGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new AiGateway(chatAdapter, usageRepository);
        ReflectionTestUtils.setField(gateway, "extractorModel", "claude-haiku-4-5");
        ReflectionTestUtils.setField(gateway, "curatorModel", "claude-sonnet-4-6");
        ReflectionTestUtils.setField(gateway, "dailyRequestCap", 10);
    }

    @Test
    void disabledWhenNoKey() {
        when(chatAdapter.isConfigured()).thenReturn(false);
        assertThrows(AiGateway.AiUnavailableException.class,
                () -> gateway.complete(AiActivity.TEST_CLASSIFY, "m", "s", "u", 100));
        verify(chatAdapter, never()).complete(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void dailyCapBlocksCalls() {
        when(chatAdapter.isConfigured()).thenReturn(true);
        when(usageRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(10L);
        assertThrows(AiGateway.AiUnavailableException.class,
                () -> gateway.complete(AiActivity.RULE_SUGGESTION, "m", "s", "u", 100));
        verify(chatAdapter, never()).complete(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void successLogsUsageWithCost() {
        when(chatAdapter.isConfigured()).thenReturn(true);
        when(usageRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);
        when(chatAdapter.complete("claude-haiku-4-5", "sys", "user", 100))
                .thenReturn(new AiChatPort.ChatResult("ok", 1000, 500));

        var text = gateway.complete(AiActivity.RULE_SUGGESTION, "claude-haiku-4-5", "sys", "user", 100);

        assertEquals("ok", text);
        var captor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(usageRepository).save(captor.capture());
        var logged = captor.getValue();
        assertEquals(AiActivity.RULE_SUGGESTION, logged.getActivity());
        assertEquals(1000, logged.getInputTokens());
        assertEquals(500, logged.getOutputTokens());
        // 1000 * $1/M + 500 * $5/M = 0.0010 + 0.0025 = 0.0035
        assertEquals(0, logged.getCostUsd().compareTo(new BigDecimal("0.003500")));
    }

    @Test
    void failureIsLoggedAndWrapped() {
        when(chatAdapter.isConfigured()).thenReturn(true);
        when(usageRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);
        when(chatAdapter.complete(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        assertThrows(AiGateway.AiUnavailableException.class,
                () -> gateway.complete(AiActivity.ANOMALY_SCAN, "claude-sonnet-4-6", "s", "u", 100));

        var captor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(usageRepository).save(captor.capture());
        assertEquals(false, captor.getValue().isSuccess());
    }

    @Test
    void costEstimateFallsBackForUnknownModel() {
        // Unknown model → default $3/$15 pricing.
        assertEquals(0, AiGateway.estimateCostUsd("mystery-model", 1_000_000, 0)
                .compareTo(new BigDecimal("3.000000")));
    }
}
