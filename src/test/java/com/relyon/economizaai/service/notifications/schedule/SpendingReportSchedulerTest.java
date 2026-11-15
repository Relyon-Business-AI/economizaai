package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.WhatsAppDispatcher;
import com.relyon.economizaai.service.notifications.WhatsAppReportService;
import com.relyon.economizaai.service.notifications.WhatsAppReportService.SpendingReportNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpendingReportSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private WhatsAppReportService reportService;
    @Mock private NotificationService notificationService;

    private SpendingReportScheduler scheduler(boolean enabled) {
        return new SpendingReportScheduler(userRepository, reportService, notificationService, enabled);
    }

    private static User candidate() {
        return User.builder().id(UUID.randomUUID()).email("report@economizaai.app").build();
    }

    private NotificationPayload onlyPayloadSent() {
        var sent = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(sent.capture());
        return sent.getValue();
    }

    @Test
    void bothRunsAreInertWhileTheFlagIsOff() {
        scheduler(false).sendWeeklyReports();
        scheduler(false).sendMonthlyReports();

        verifyNoInteractions(userRepository, reportService, notificationService);
    }

    @Test
    void weeklyRunSendsEachReportThroughTheNotificationServiceWithTheOneLineSummary() {
        var user = candidate();
        when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of(user));
        when(reportService.buildSpendingReportNotice(user, SpendingReportFrequency.WEEKLY)).thenReturn(
                new SpendingReportNotice("Seu resumo da semana", "Seus gastos de 07/11 a 14/11\n\nTotal: R$ 150,00",
                        "R$ 150,00 em 2 compra(s) essa semana.", true));
        when(notificationService.notify(any())).thenReturn(Notification.builder().build());

        scheduler(true).sendWeeklyReports();

        var payload = onlyPayloadSent();
        assertEquals(user, payload.user());
        assertEquals(NotificationType.WEEKLY_REPORT, payload.type());
        assertEquals("Seu resumo da semana", payload.title());
        assertEquals("Seus gastos de 07/11 a 14/11\n\nTotal: R$ 150,00", payload.body());
        assertEquals("R$ 150,00 em 2 compra(s) essa semana.",
                payload.extras().get(WhatsAppDispatcher.EXTRA_ONE_LINE_TEXT));
    }

    @Test
    void weeklyRunOnlyReachesTheWeeklyCandidates() {
        when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of());

        scheduler(true).sendWeeklyReports();

        verify(userRepository, never()).findMonthlyReportCandidates();
        verifyNoInteractions(reportService, notificationService);
    }

    @Test
    void monthlyRunSendsTheMonthlyReportToTheMonthlyCandidates() {
        var user = candidate();
        when(userRepository.findMonthlyReportCandidates()).thenReturn(List.of(user));
        when(reportService.buildSpendingReportNotice(user, SpendingReportFrequency.MONTHLY)).thenReturn(
                new SpendingReportNotice("Seu resumo de outubro", "Seus gastos de outubro de 2026\n\nTotal: R$ 900,00",
                        "R$ 900,00 em 8 compra(s) em outubro.", true));
        when(notificationService.notify(any())).thenReturn(Notification.builder().build());

        scheduler(true).sendMonthlyReports();

        var payload = onlyPayloadSent();
        assertEquals(user, payload.user());
        assertEquals(NotificationType.MONTHLY_REPORT, payload.type());
        assertEquals("Seu resumo de outubro", payload.title());
        assertEquals("Seus gastos de outubro de 2026\n\nTotal: R$ 900,00", payload.body());
        assertEquals("R$ 900,00 em 8 compra(s) em outubro.",
                payload.extras().get(WhatsAppDispatcher.EXTRA_ONE_LINE_TEXT));
        verify(userRepository, never()).findWeeklyReportCandidates();
    }

    @Test
    void aPeriodWithoutPurchasesSendsNothing() {
        var user = candidate();
        when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of(user));
        when(userRepository.findMonthlyReportCandidates()).thenReturn(List.of(user));
        when(reportService.buildSpendingReportNotice(user, SpendingReportFrequency.WEEKLY)).thenReturn(
                new SpendingReportNotice("Seu resumo da semana", "Nenhuma nota essa semana.",
                        "Nenhuma nota essa semana.", false));
        when(reportService.buildSpendingReportNotice(user, SpendingReportFrequency.MONTHLY)).thenReturn(
                new SpendingReportNotice("Seu resumo de outubro", "Nenhuma nota em outubro.",
                        "Nenhuma nota em outubro.", false));

        scheduler(true).sendWeeklyReports();
        scheduler(true).sendMonthlyReports();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void oneUsersFailureDoesNotStopTheRun() {
        var failing = candidate();
        var healthy = candidate();
        when(userRepository.findMonthlyReportCandidates()).thenReturn(List.of(failing, healthy));
        when(reportService.buildSpendingReportNotice(failing, SpendingReportFrequency.MONTHLY))
                .thenThrow(new IllegalStateException("report broke"));
        when(reportService.buildSpendingReportNotice(healthy, SpendingReportFrequency.MONTHLY)).thenReturn(
                new SpendingReportNotice("Título", "Corpo", "Linha", true));

        assertDoesNotThrow(() -> scheduler(true).sendMonthlyReports());

        verify(notificationService, times(1)).notify(any());
    }
}
