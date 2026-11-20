package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import com.relyon.economizaai.service.notifications.SpendingReportDeliveryLedger;
import com.relyon.economizaai.service.notifications.WhatsAppReportService;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The send times of the scheduled notices are the ones set in the admin, read at each hourly
 * tick: with the defaults each job runs when its old fixed schedule did, and a changed value
 * moves the run with no restart.
 */
@ExtendWith(MockitoExtension.class)
class SendTimeSettingsTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private InsightsRepository insightsRepository;
    @Mock private ConsumptionIntelligenceService consumptionService;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService messageService;
    @Mock private NotificationSettingsService settingsService;
    @Mock private UserRepository userRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private WhatsAppReportService reportService;
    @Mock private SpendingReportDeliveryLedger deliveryLedger;

    /** November 2026: the 16th is a Monday, the 17th a Tuesday, the 18th a Wednesday. */
    private static ZonedDateTime november(int day, int hour) {
        return ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, BrazilClock.ZONE);
    }

    private static ZonedDateTime december(int day, int hour) {
        return ZonedDateTime.of(2026, 12, day, hour, 0, 0, 0, BrazilClock.ZONE);
    }

    // ---- replenishment reminders ----

    private ReplenishmentService replenishment() {
        return new ReplenishmentService(ruleRepository, consumptionService, notificationService,
                messageService, settingsService);
    }

    @Test
    void replenishment_runsOnceADayAtTenByDefault() {
        var service = replenishment();

        for (var hour = 0; hour < 24; hour++) {
            service.runIfSendHour(november(16, hour));
        }

        verify(settingsService, times(1)).isEnabled(NotificationJob.REPLENISHMENT);
        service.runIfSendHour(november(16, 10));
        verify(settingsService, times(1)).isEnabled(NotificationJob.REPLENISHMENT);
    }

    @Test
    void replenishment_followsTheHourSetInTheAdmin() {
        var service = replenishment();
        service.runIfSendHour(november(16, 10));
        verify(settingsService, times(1)).isEnabled(NotificationJob.REPLENISHMENT);

        service.setSendHour(15);

        service.runIfSendHour(november(17, 10));
        verify(settingsService, times(1)).isEnabled(NotificationJob.REPLENISHMENT);
        service.runIfSendHour(november(17, 15));
        verify(settingsService, times(2)).isEnabled(NotificationJob.REPLENISHMENT);
    }

    // ---- budget check ----

    private BudgetMonitorService budgetMonitor() {
        return new BudgetMonitorService(ruleRepository, insightsRepository, notificationService,
                messageService, settingsService, userRepository);
    }

    @Test
    void budget_isCheckedAtNineAndAtSixInTheEveningByDefault() {
        var service = budgetMonitor();

        for (var hour = 0; hour < 24; hour++) {
            service.runIfCheckHour(november(16, hour));
        }

        verify(settingsService, times(2)).isEnabled(NotificationJob.BUDGET_ALERT);
    }

    @Test
    void budget_followsTheHoursSetInTheAdmin() {
        var service = budgetMonitor();
        service.runIfCheckHour(november(16, 9));
        verify(settingsService, times(1)).isEnabled(NotificationJob.BUDGET_ALERT);

        service.setCheckHours(List.of(7, 12, 20));

        service.runIfCheckHour(november(16, 18));
        verify(settingsService, times(1)).isEnabled(NotificationJob.BUDGET_ALERT);
        service.runIfCheckHour(november(16, 20));
        service.runIfCheckHour(november(17, 7));
        service.runIfCheckHour(november(17, 12));
        verify(settingsService, times(4)).isEnabled(NotificationJob.BUDGET_ALERT);
    }

    // ---- spending reports ----

    private SpendingReportScheduler spendingReports() {
        lenient().when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of());
        lenient().when(userRepository.findMonthlyReportCandidates()).thenReturn(List.of());
        return new SpendingReportScheduler(userRepository, reportService, notificationService, deliveryLedger, true);
    }

    @Test
    void weeklyReport_goesOutOnMondayAtTenByDefault() {
        var scheduler = spendingReports();

        scheduler.sendReportsIfTheirTime(november(16, 9));
        scheduler.sendReportsIfTheirTime(november(17, 10));
        verify(userRepository, never()).findWeeklyReportCandidates();

        scheduler.sendReportsIfTheirTime(november(16, 10));
        verify(userRepository, times(1)).findWeeklyReportCandidates();
        verify(userRepository, never()).findMonthlyReportCandidates();
    }

    @Test
    void weeklyReport_followsTheWeekdayAndHourSetInTheAdmin() {
        var scheduler = spendingReports();
        scheduler.setWeeklyDay(DayOfWeek.WEDNESDAY);
        scheduler.setWeeklyHour(8);

        scheduler.sendReportsIfTheirTime(november(16, 10));
        scheduler.sendReportsIfTheirTime(november(18, 10));
        verify(userRepository, never()).findWeeklyReportCandidates();

        scheduler.sendReportsIfTheirTime(november(18, 8));
        verify(userRepository, times(1)).findWeeklyReportCandidates();
    }

    @Test
    void monthlyReport_goesOutOnTheFirstAtTenByDefault() {
        var scheduler = spendingReports();

        scheduler.sendReportsIfTheirTime(december(2, 10));
        scheduler.sendReportsIfTheirTime(december(1, 9));
        verify(userRepository, never()).findMonthlyReportCandidates();

        scheduler.sendReportsIfTheirTime(december(1, 10));
        verify(userRepository, times(1)).findMonthlyReportCandidates();
    }

    @Test
    void monthlyReport_followsTheDayAndHourSetInTheAdmin() {
        var scheduler = spendingReports();
        scheduler.setMonthlyDay(5);
        scheduler.setMonthlyHour(14);

        scheduler.sendReportsIfTheirTime(december(1, 10));
        scheduler.sendReportsIfTheirTime(december(5, 10));
        verify(userRepository, never()).findMonthlyReportCandidates();

        scheduler.sendReportsIfTheirTime(december(5, 14));
        verify(userRepository, times(1)).findMonthlyReportCandidates();
    }

    @Test
    void aMondayThatIsAlsoTheMonthlyDaySendsBothReports() {
        var scheduler = spendingReports();
        scheduler.setMonthlyDay(16);

        scheduler.sendReportsIfTheirTime(november(16, 10));

        verify(userRepository, times(1)).findWeeklyReportCandidates();
        verify(userRepository, times(1)).findMonthlyReportCandidates();
    }

    // ---- push opt-in invite ----

    private PushOptInNudgeScheduler pushOptInNudge() {
        lenient().when(userRepository.findPushOptInNudgeCandidates(any(), any(), any())).thenReturn(List.of());
        return new PushOptInNudgeScheduler(userRepository, notificationRepository, messageService);
    }

    @Test
    void pushInvite_goesOutOnTuesdayAtNoonByDefault() {
        var scheduler = pushOptInNudge();

        scheduler.nudgeIfSendTime(november(16, 12));
        scheduler.nudgeIfSendTime(november(17, 11));
        verify(userRepository, never()).findPushOptInNudgeCandidates(any(), any(), any());

        scheduler.nudgeIfSendTime(november(17, 12));
        verify(userRepository, times(1)).findPushOptInNudgeCandidates(any(), any(), any());
    }

    @Test
    void pushInvite_followsTheWeekdayAndHourSetInTheAdmin() {
        var scheduler = pushOptInNudge();
        scheduler.setWeekday(DayOfWeek.MONDAY);
        scheduler.setSendHour(18);

        scheduler.nudgeIfSendTime(november(17, 12));
        verify(userRepository, never()).findPushOptInNudgeCandidates(any(), any(), any());

        scheduler.nudgeIfSendTime(november(16, 18));
        verify(userRepository, times(1)).findPushOptInNudgeCandidates(any(), any(), any());
    }

    @Test
    void pushInvite_isRepeatedOnlyAfterTheDaysSetInTheAdmin() {
        var scheduler = pushOptInNudge();

        scheduler.nudgeUsersWithoutPushToken();
        assertInvitedBeforeDaysAgo(30, 1);

        scheduler.setRenudgeDays(10);
        scheduler.nudgeUsersWithoutPushToken();
        assertInvitedBeforeDaysAgo(10, 2);
    }

    private void assertInvitedBeforeDaysAgo(int days, int runsSoFar) {
        var nudgedAfter = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository, times(runsSoFar)).findPushOptInNudgeCandidates(nudgedAfter.capture(), any(), any());
        var expected = BrazilClock.nowDateTime().minusDays(days);
        var distance = Duration.between(nudgedAfter.getValue(), expected).abs();
        assertEquals(runsSoFar, nudgedAfter.getAllValues().size());
        assertTrue(distance.toMinutes() < 5, "cutoff " + nudgedAfter.getValue() + " expected about " + expected);
    }
}
