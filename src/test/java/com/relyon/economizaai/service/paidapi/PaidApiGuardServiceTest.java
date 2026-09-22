package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.PaidApiUnavailableException;
import com.relyon.economizaai.model.PaidApiCall;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidApiGuardServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-07-09T12:00:00Z");

    @Mock private PaidApiCallRepository repository;

    private PaidApiGuardProperties properties;
    private PaidApiGuardService service;

    @BeforeEach
    void setUp() {
        properties = new PaidApiGuardProperties();
        properties.setInfosimplesDailyCapPerUser(3);
        properties.setCaptchaDailyCapPerUser(5);
        properties.setTwilioDailyCapPerUser(2);
        properties.setInfosimplesFailureThreshold(3);
        properties.setBreakerWindowSeconds(600);
        properties.setBreakerCooldownSeconds(300);
        properties.setDailyGlobalBudgetCents(1000);
        service = new PaidApiGuardService(properties, repository);
        service.useClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void assertWithinDailyCap_underCap_passes() {
        when(repository.countByUserIdAndServiceAndCreatedAtGreaterThanEqual(eq(USER), eq(PaidApiService.INFOSIMPLES), any()))
                .thenReturn(2L);
        assertDoesNotThrow(() -> service.assertWithinDailyCap(USER, PaidApiService.INFOSIMPLES));
    }

    @Test
    void assertWithinDailyCap_atCap_throws() {
        when(repository.countByUserIdAndServiceAndCreatedAtGreaterThanEqual(eq(USER), eq(PaidApiService.INFOSIMPLES), any()))
                .thenReturn(3L);
        assertThrows(PaidApiQuotaExceededException.class,
                () -> service.assertWithinDailyCap(USER, PaidApiService.INFOSIMPLES));
    }

    @Test
    void assertWithinDailyCap_twilioAtCap_throws() {
        when(repository.countByUserIdAndServiceAndCreatedAtGreaterThanEqual(eq(USER), eq(PaidApiService.TWILIO_MESSAGE), any()))
                .thenReturn(2L);
        assertThrows(PaidApiQuotaExceededException.class,
                () -> service.assertWithinDailyCap(USER, PaidApiService.TWILIO_MESSAGE));
    }

    @Test
    void assertWithinDailyCap_nullUser_skipsCountAndPasses() {
        assertDoesNotThrow(() -> service.assertWithinDailyCap(null, PaidApiService.INFOSIMPLES));
        verify(repository, never()).countByUserIdAndServiceAndCreatedAtGreaterThanEqual(any(), any(), any());
    }

    @Test
    void assertWithinDailyCap_enforcementDisabled_skipsCountAndPasses() {
        properties.setEnabled(false);
        assertDoesNotThrow(() -> service.assertWithinDailyCap(USER, PaidApiService.INFOSIMPLES));
        verify(repository, never()).countByUserIdAndServiceAndCreatedAtGreaterThanEqual(any(), any(), any());
    }

    @Test
    void assertWithinDailyCap_unlimitedCap_skipsCount() {
        properties.setInfosimplesDailyCapPerUser(0);
        assertDoesNotThrow(() -> service.assertWithinDailyCap(USER, PaidApiService.INFOSIMPLES));
        verify(repository, never()).countByUserIdAndServiceAndCreatedAtGreaterThanEqual(any(), any(), any());
    }

    @Test
    void assertUnderGlobalBudget_belowCeiling_passes() {
        when(repository.sumCostCentsSince(any())).thenReturn(999L);
        assertDoesNotThrow(() -> service.assertUnderGlobalBudget());
    }

    @Test
    void assertUnderGlobalBudget_atCeiling_throws() {
        when(repository.sumCostCentsSince(any())).thenReturn(1000L);
        assertThrows(PaidApiBudgetExceededException.class, () -> service.assertUnderGlobalBudget());
    }

    @Test
    void assertUnderGlobalBudget_unlimitedBudget_skipsSum() {
        properties.setDailyGlobalBudgetCents(0);
        assertDoesNotThrow(() -> service.assertUnderGlobalBudget());
        verify(repository, never()).sumCostCentsSince(any());
    }

    @Test
    void assertUnderGlobalBudget_enforcementDisabled_skipsSum() {
        properties.setEnabled(false);
        assertDoesNotThrow(() -> service.assertUnderGlobalBudget());
        verify(repository, never()).sumCostCentsSince(any());
    }

    @Test
    void recordSuccess_writesLedgerRow() {
        service.recordSuccess(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");

        var captor = ArgumentCaptor.forClass(PaidApiCall.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(USER, saved.getUserId());
        assertEquals(PaidApiService.INFOSIMPLES, saved.getService());
        assertEquals("CE", saved.getUf());
        assertTrue(saved.isSuccess());
        assertEquals(PaidApiService.INFOSIMPLES.defaultCostCents(), saved.getEstimatedCostCents());
    }

    @Test
    void recordFailure_writesFailedLedgerRow() {
        service.recordFailure(USER, PaidApiService.CAPTCHA_SOLVE, "MS", null);

        var captor = ArgumentCaptor.forClass(PaidApiCall.class);
        verify(repository).save(captor.capture());
        assertFalse(captor.getValue().isSuccess());
    }

    @Test
    void circuitBreaker_tripsAfterThresholdFailures_thenReopensAfterCooldown() {
        // Below threshold: circuit stays closed.
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        assertDoesNotThrow(() -> service.assertCircuitClosed(PaidApiService.INFOSIMPLES));

        // Third failure trips it.
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        assertThrows(PaidApiUnavailableException.class,
                () -> service.assertCircuitClosed(PaidApiService.INFOSIMPLES));

        // Still open just before cooldown elapses.
        service.useClock(Clock.fixed(T0.plus(Duration.ofSeconds(299)), ZoneOffset.UTC));
        assertThrows(PaidApiUnavailableException.class,
                () -> service.assertCircuitClosed(PaidApiService.INFOSIMPLES));

        // Reopens (closes) once the cooldown passes.
        service.useClock(Clock.fixed(T0.plus(Duration.ofSeconds(301)), ZoneOffset.UTC));
        assertDoesNotThrow(() -> service.assertCircuitClosed(PaidApiService.INFOSIMPLES));
    }

    @Test
    void circuitBreaker_successResetsFailureCount() {
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        service.recordSuccess(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");

        // Two more failures — only 2 in the window now, breaker stays closed.
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        service.recordFailure(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples");
        assertDoesNotThrow(() -> service.assertCircuitClosed(PaidApiService.INFOSIMPLES));
    }

    @Test
    void ledgerWriteFailure_isSwallowed() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.recordSuccess(USER, PaidApiService.INFOSIMPLES, "CE", "infosimples"));
    }
}
