package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.InvalidDashboardPeriodException;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private PriceObservationAuditRepository priceObservationAuditRepository;
    @Mock private AdminUserService adminUserService;
    @Mock private AdminReceiptService adminReceiptService;
    @InjectMocks private AdminDashboardService service;

    @Test
    void defaultsToTheLastSevenDays() {
        var today = BrazilClock.today();

        var window = service.resolveWindow(null, null, null);

        assertEquals("7d", window.period());
        assertEquals(today.minusDays(6), window.from());
        assertEquals(today, window.to());
        assertEquals(7, window.days());
    }

    @Test
    void thirtyDayPresetCoversThirtyDaysEndingToday() {
        var window = service.resolveWindow("30D", null, null);

        assertEquals("30d", window.period());
        assertEquals(30, window.days());
        assertEquals(BrazilClock.today(), window.to());
    }

    @Test
    void allTimeStartsOnTheFirstSignupDay() {
        var firstSignupUtc = LocalDateTime.of(2026, 5, 5, 15, 0);
        when(userRepository.findEarliestCreatedAt()).thenReturn(Optional.of(firstSignupUtc));

        var window = service.resolveWindow("all", null, null);

        assertEquals("all", window.period());
        assertEquals(LocalDate.of(2026, 5, 5), window.from());
        assertEquals(BrazilClock.today(), window.to());
    }

    @Test
    void allTimeOnAnEmptyDatabaseIsJustToday() {
        when(userRepository.findEarliestCreatedAt()).thenReturn(Optional.empty());

        var window = service.resolveWindow("all", null, null);

        assertEquals(BrazilClock.today(), window.from());
        assertEquals(1, window.days());
    }

    @Test
    void customRangeWinsOverThePeriodAndIsInclusive() {
        var from = BrazilClock.today().minusDays(40);
        var to = BrazilClock.today().minusDays(10);

        var window = service.resolveWindow("7d", from, to);

        assertEquals("custom", window.period());
        assertEquals(from, window.from());
        assertEquals(to, window.to());
        assertEquals(31, window.days());
    }

    @Test
    void rejectsInvalidPeriodsAndRanges() {
        var today = BrazilClock.today();

        assertThrows(InvalidDashboardPeriodException.class, () -> service.resolveWindow("90d", null, null));
        assertThrows(InvalidDashboardPeriodException.class, () -> service.resolveWindow(null, today.minusDays(3), null));
        assertThrows(InvalidDashboardPeriodException.class, () -> service.resolveWindow(null, null, today));
        assertThrows(InvalidDashboardPeriodException.class,
                () -> service.resolveWindow(null, today, today.minusDays(1)));
        assertThrows(InvalidDashboardPeriodException.class,
                () -> service.resolveWindow(null, today.minusDays(1), today.plusDays(1)));
        assertThrows(InvalidDashboardPeriodException.class,
                () -> service.resolveWindow(null, today.minusDays(AdminDashboardService.MAX_WINDOW_DAYS), today));
    }

    @Test
    void aggregatesTheWindowAndKeepsTotalsAllTime() {
        var today = BrazilClock.today();
        var windowStart = BrazilClock.startOfDayUtc(today.minusDays(6));
        var windowEnd = BrazilClock.startOfDayUtc(today.plusDays(1));
        var signupToday = BrazilClock.startOfDayUtc(today).plusHours(2);

        when(userRepository.countUsers(false)).thenReturn(500L);
        when(userRepository.countSignupsBetween(windowStart, windowEnd, false)).thenReturn(40L);
        when(userRepository.countVerifiedBetween(windowStart, windowEnd, false)).thenReturn(30L);
        when(userRepository.countActivatedBetween(windowStart, windowEnd, false)).thenReturn(10L);
        when(userRepository.signupTimelineBetween(windowStart, windowEnd, false)).thenReturn(List.<Object[]>of(
                new Object[]{signupToday, AcquisitionChannel.APP_STORE},
                new Object[]{signupToday, null}));
        when(receiptRepository.countReceipts(false)).thenReturn(9000L);
        when(receiptRepository.countCreatedBetween(windowStart, windowEnd, false)).thenReturn(300L);
        when(receiptRepository.statusBreakdownBetween(windowStart, windowEnd, false)).thenReturn(List.<Object[]>of(
                new Object[]{ReceiptStatus.CONFIRMED, 90L},
                new Object[]{ReceiptStatus.FAILED_PARSE, 10L},
                new Object[]{ReceiptStatus.PROCESSING, 50L}));
        when(receiptRepository.sumConfirmedTotal(false)).thenReturn(new BigDecimal("150000.555"));
        when(receiptRepository.sumConfirmedTotalCreatedBetween(windowStart, windowEnd, false))
                .thenReturn(new BigDecimal("4200.10"));
        when(receiptRepository.countActiveHouseholdsBetween(any(), eq(windowEnd), eq(false))).thenReturn(200L);
        when(receiptRepository.countActiveHouseholdsBetween(BrazilClock.startOfDayUtc(today), windowEnd, false))
                .thenReturn(50L);
        when(receiptRepository.confirmedReceiptsByUfBetween(windowStart, windowEnd, false)).thenReturn(List.<Object[]>of(
                new Object[]{"SP", 20L, new BigDecimal("900.00")},
                new Object[]{"RS", 70L, new BigDecimal("3300.10")}));
        when(receiptRepository.usersByPredominantUfBetween(windowStart, windowEnd, false)).thenReturn(List.<Object[]>of(
                new Object[]{"RS", 12L},
                new Object[]{"SC", 3L}));
        when(priceObservationRepository.count()).thenReturn(5000L);
        when(priceObservationAuditRepository.countDistinctContributingHouseholds()).thenReturn(40L);
        when(adminUserService.list(any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(Page.empty());
        when(adminReceiptService.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(Page.empty());

        var dashboard = service.dashboard("7d", null, null, false);

        assertEquals(500L, dashboard.users().total());
        assertEquals(40L, dashboard.users().inPeriod());
        assertEquals(9000L, dashboard.receipts().total());
        assertEquals(300L, dashboard.receipts().inPeriod());
        assertEquals(0.9d, dashboard.receipts().parseRate(), 0.0001); // 90 / (90 + 10); PROCESSING is ignored
        assertEquals(new BigDecimal("150000.56"), dashboard.spend().total());
        assertEquals(new BigDecimal("4200.10"), dashboard.spend().inPeriod());
        assertEquals(200L, dashboard.engagement().activeHouseholdsInPeriod());
        assertEquals(50L, dashboard.engagement().activeHouseholds1d());
        assertEquals(0.25d, dashboard.engagement().stickiness(), 0.0001); // 50 DAU / 200 MAU
        assertEquals(40L, dashboard.funnel().signups());
        assertEquals(0.75d, dashboard.funnel().verifiedRate(), 0.0001);
        assertEquals(0.25d, dashboard.funnel().activatedRate(), 0.0001);
        assertEquals(5000L, dashboard.priceIndex().priceObservations());

        assertEquals(7, dashboard.timeline().size());
        var lastDay = dashboard.timeline().get(6);
        assertEquals(today, lastDay.date());
        assertEquals(2L, lastDay.total());
        assertEquals(1L, lastDay.byChannel().get("APP_STORE"));
        assertEquals(1L, lastDay.byChannel().get("UNKNOWN"));
        assertEquals(0L, dashboard.timeline().get(0).total());

        assertEquals(List.of("RS", "SP", "SC"), dashboard.byState().stream().map(row -> row.uf()).toList());
        assertEquals(70L, dashboard.byState().get(0).receipts());
        assertEquals(12L, dashboard.byState().get(0).users());
        assertEquals(new BigDecimal("3300.10"), dashboard.byState().get(0).spend());
        assertEquals(3L, dashboard.byState().get(2).users());
        assertEquals(0L, dashboard.byState().get(2).receipts());
    }

    @Test
    void passesTheInternalAccountsToggleThrough() {
        when(adminUserService.list(any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(Page.empty());
        when(adminReceiptService.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(Page.empty());

        service.dashboard("30d", null, null, true);

        verify(userRepository).countUsers(true);
        verify(receiptRepository).countReceipts(true);
        verify(adminUserService).list(any(), any(), any(), any(), any(), any(), eq(true), any());
    }
}
