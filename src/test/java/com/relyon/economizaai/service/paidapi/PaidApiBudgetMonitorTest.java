package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository.ServiceSpend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidApiBudgetMonitorTest {

    private static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");
    private static final String TODAY = "2026-11-11";

    @Mock private PaidApiCallRepository repository;
    @Mock private CostAlertService costAlertService;

    private PaidApiGuardProperties properties;
    private PaidApiBudgetMonitor monitor;

    @BeforeEach
    void setUp() {
        properties = new PaidApiGuardProperties(); // approved defaults: Infosimples R$120, captcha R$30...
        monitor = new PaidApiBudgetMonitor(properties, repository, costAlertService);
        monitor.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(costAlertService.isEnabled()).thenReturn(true);
    }

    private static ServiceSpend spend(PaidApiService service, long costCents) {
        var row = mock(ServiceSpend.class);
        when(row.getService()).thenReturn(service);
        when(row.getCostCents()).thenReturn(costCents);
        return row;
    }

    private void spentToday(ServiceSpend... rows) {
        when(repository.spendByService(OffsetDateTime.parse("2026-11-11T00:00:00Z"))).thenReturn(List.of(rows));
    }

    @Test
    void below80Percent_raisesNothing() {
        spentToday(spend(PaidApiService.INFOSIMPLES, 9_599));

        monitor.checkDailyBudgets();

        verify(costAlertService, never()).raise(anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(costAlertService, never()).dispatchPending();
    }

    @Test
    void at80Percent_raisesTheWarningForThatServiceAndDay_withSpentAndLimitInReais() {
        spentToday(spend(PaidApiService.INFOSIMPLES, 9_600));
        when(costAlertService.raise(eq("BUDGET:INFOSIMPLES"), eq(80), eq(TODAY), anyString(), anyString()))
                .thenReturn(true);

        monitor.checkDailyBudgets();

        var headline = ArgumentCaptor.forClass(String.class);
        verify(costAlertService).raise(eq("BUDGET:INFOSIMPLES"), eq(80), eq(TODAY), headline.capture(), anyString());
        assertTrue(headline.getValue().contains("Infosimples"));
        assertTrue(headline.getValue().contains("80%"));
        assertTrue(headline.getValue().contains("R$ 96,00"));
        assertTrue(headline.getValue().contains("R$ 120,00"));
        verify(costAlertService).dispatchPending();
    }

    @Test
    void at100Percent_raisesTheLimitAlert_sayingWhatStops() {
        spentToday(spend(PaidApiService.CAPTCHA_SOLVE, 3_003));
        when(costAlertService.raise(eq("BUDGET:CAPTCHA_SOLVE"), eq(100), eq(TODAY), anyString(), anyString()))
                .thenReturn(true);

        monitor.checkDailyBudgets();

        var headline = ArgumentCaptor.forClass(String.class);
        verify(costAlertService).raise(eq("BUDGET:CAPTCHA_SOLVE"), eq(100), eq(TODAY), headline.capture(), anyString());
        assertTrue(headline.getValue().contains("ATINGIDO"));
        assertTrue(headline.getValue().contains("R$ 30,03"));
        assertTrue(headline.getValue().contains("R$ 30,00"));
        assertTrue(headline.getValue().contains("captcha"));
        verify(costAlertService, never()).raise(anyString(), eq(80), anyString(), anyString(), anyString());
    }

    @Test
    void eachServiceIsJudgedAgainstItsOwnBudget() {
        spentToday(spend(PaidApiService.INFOSIMPLES, 3_000),   // 25% de R$120
                spend(PaidApiService.LLM_HTML_RESCUE, 1_200)); // 80% de R$15

        monitor.checkDailyBudgets();

        verify(costAlertService, times(1)).raise(anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(costAlertService).raise(eq("BUDGET:LLM_HTML_RESCUE"), eq(80), eq(TODAY), anyString(), anyString());
    }

    @Test
    void alreadyAlertedToday_doesNotTriggerADispatch() {
        spentToday(spend(PaidApiService.INFOSIMPLES, 9_600));
        when(costAlertService.raise(anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(false);

        monitor.checkDailyBudgets();

        verify(costAlertService, never()).dispatchPending();
    }

    @Test
    void unlimitedBudget_neverAlerts() {
        properties.setInfosimplesDailyBudgetCents(0);
        spentToday(spend(PaidApiService.INFOSIMPLES, 999_999));

        monitor.checkDailyBudgets();

        verify(costAlertService, never()).raise(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void optionalOverallCeiling_alertsOnTheSumOfAllServices() {
        properties.setDailyGlobalBudgetCents(1_000);
        spentToday(spend(PaidApiService.INFOSIMPLES, 500), spend(PaidApiService.CAPTCHA_SOLVE, 500));

        monitor.checkDailyBudgets();

        verify(costAlertService).raise(eq("BUDGET:TOTAL"), eq(100), eq(TODAY), anyString(), anyString());
    }

    @Test
    void guardOrAlertsDisabled_skipsTheQuery() {
        properties.setEnabled(false);
        monitor.checkDailyBudgets();

        properties.setEnabled(true);
        when(costAlertService.isEnabled()).thenReturn(false);
        monitor.checkDailyBudgets();

        verify(repository, never()).spendByService(any());
    }

    @Test
    void alertKey_isStablePerService() {
        assertEquals("BUDGET:TWILIO_MESSAGE", PaidApiBudgetMonitor.alertKey(PaidApiService.TWILIO_MESSAGE));
    }
}
