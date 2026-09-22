package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository.ServiceSpend;
import com.relyon.economizaai.repository.PaidApiCallRepository.StateSpend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostReportServiceTest {

    @Mock private PaidApiCallRepository repository;
    private CostReportService service;

    @BeforeEach
    void setUp() {
        var properties = new PaidApiGuardProperties();
        properties.setDailyGlobalBudgetCents(5000);
        service = new CostReportService(repository, properties);
    }

    @Test
    void report_aggregatesSpendAndConvertsCentsToReais() {
        var services = List.of(
                serviceSpend(PaidApiService.INFOSIMPLES, 10, 240, 2),
                serviceSpend(PaidApiService.CAPTCHA_SOLVE, 30, 90, 0));
        var states = List.of(
                stateSpend("CE", 10, 240),
                stateSpend("SP", 30, 90));
        when(repository.spendByService(any())).thenReturn(services);
        when(repository.spendByState(any())).thenReturn(states);
        when(repository.sumCostCentsSince(any())).thenReturn(120L);

        var report = service.report(30);

        assertEquals(30, report.windowDays());
        assertEquals(40, report.totalCalls());
        assertEquals(330, report.totalCostCents());
        assertEquals(new BigDecimal("3.30"), report.totalCostReais());
        assertEquals(5000, report.dailyGlobalBudgetCents());
        assertEquals(new BigDecimal("1.20"), report.spentTodayReais());
        assertEquals(2, report.byService().size());
        assertEquals(new BigDecimal("2.40"), report.byService().get(0).costReais());
        assertEquals("CE", report.byState().get(0).uf());
    }

    @Test
    void report_clampsNonPositiveWindowToOneDay() {
        when(repository.spendByService(any())).thenReturn(List.of());
        when(repository.spendByState(any())).thenReturn(List.of());
        when(repository.sumCostCentsSince(any())).thenReturn(0L);

        assertEquals(1, service.report(0).windowDays());
    }

    private static ServiceSpend serviceSpend(PaidApiService service, long calls, long cents, long failures) {
        var row = mock(ServiceSpend.class);
        when(row.getService()).thenReturn(service);
        when(row.getCalls()).thenReturn(calls);
        when(row.getCostCents()).thenReturn(cents);
        when(row.getFailures()).thenReturn(failures);
        return row;
    }

    private static StateSpend stateSpend(String uf, long calls, long cents) {
        var row = mock(StateSpend.class);
        when(row.getUf()).thenReturn(uf);
        when(row.getCalls()).thenReturn(calls);
        when(row.getCostCents()).thenReturn(cents);
        return row;
    }
}
