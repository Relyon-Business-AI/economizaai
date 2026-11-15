package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.WhatsAppDispatcher;
import com.relyon.economizaai.service.notifications.WhatsAppReportService;
import com.relyon.economizaai.service.notifications.WhatsAppReportService.WeeklyReportNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppWeeklyReportSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private WhatsAppReportService reportService;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService messageService;

    private WhatsAppWeeklyReportScheduler scheduler(boolean enabled) {
        return new WhatsAppWeeklyReportScheduler(userRepository, reportService, notificationService,
                messageService, enabled);
    }

    private static User candidate() {
        return User.builder().id(UUID.randomUUID()).email("report@economizaai.app").build();
    }

    @Test
    void inertWhileTheFlagIsOff() {
        scheduler(false).sendWeeklyReports();

        verifyNoInteractions(userRepository, reportService, notificationService);
    }

    @Test
    void sendsEachReportThroughTheNotificationServiceWithTheOneLineSummary() {
        var user = candidate();
        when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of(user));
        when(reportService.buildWeeklyReportNotice(user)).thenReturn(
                new WeeklyReportNotice("Seus gastos de 07/11 a 14/11\n\nTotal: R$ 150,00", "R$ 150,00 em 2 compra(s) essa semana.", true));
        when(messageService.translate(eq("notification.weekly_report.title"), any(Locale.class)))
                .thenReturn("Seu resumo da semana");
        when(notificationService.notify(any())).thenReturn(Notification.builder().build());

        scheduler(true).sendWeeklyReports();

        var sent = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(sent.capture());
        assertEquals(user, sent.getValue().user());
        assertEquals(NotificationType.WEEKLY_REPORT, sent.getValue().type());
        assertEquals("Seu resumo da semana", sent.getValue().title());
        assertEquals("Seus gastos de 07/11 a 14/11\n\nTotal: R$ 150,00", sent.getValue().body());
        assertEquals("R$ 150,00 em 2 compra(s) essa semana.",
                sent.getValue().extras().get(WhatsAppDispatcher.EXTRA_ONE_LINE_TEXT));
    }

    @Test
    void aWeekWithoutPurchasesSendsNothing() {
        var user = candidate();
        when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of(user));
        when(reportService.buildWeeklyReportNotice(user)).thenReturn(
                new WeeklyReportNotice("Nenhuma nota essa semana.", "Nenhuma nota essa semana.", false));

        scheduler(true).sendWeeklyReports();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void oneUsersFailureDoesNotStopTheRun() {
        var failing = candidate();
        var healthy = candidate();
        when(userRepository.findWeeklyReportCandidates()).thenReturn(List.of(failing, healthy));
        when(reportService.buildWeeklyReportNotice(failing)).thenThrow(new IllegalStateException("report broke"));
        when(reportService.buildWeeklyReportNotice(healthy)).thenReturn(
                new WeeklyReportNotice("Corpo", "Linha", true));
        when(messageService.translate(eq("notification.weekly_report.title"), any(Locale.class))).thenReturn("Título");

        assertDoesNotThrow(() -> scheduler(true).sendWeeklyReports());

        verify(notificationService, times(1)).notify(any());
    }
}
