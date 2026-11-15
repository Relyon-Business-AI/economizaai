package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.WhatsAppDispatcher;
import com.relyon.economizaai.service.notifications.WhatsAppReportService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Proactive weekly spending report (the WEEKLY_REPORT notice) to everyone who has one of its
 * options on. INERT by default (economizaai.notifications.whatsapp.weekly-report.enabled=false)
 * — it ships built but off. Each report goes through {@link NotificationService}, which sends
 * it on the channels the user has on: e-mail (the full text) and WhatsApp (the approved
 * template, whose single {{1}} variable carries a one-line summary; skipped while no report
 * template SID is configured). A week without purchases sends nothing. Per-user failures are
 * logged, never fatal.
 *
 * <p>Deliberately not transactional: each report is read in its own short transaction and
 * the dispatch (SMTP/Twilio) runs outside any.
 */
@Slf4j
@Service
public class WhatsAppWeeklyReportScheduler {

    private final UserRepository userRepository;
    private final WhatsAppReportService reportService;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;
    private final boolean enabled;

    public WhatsAppWeeklyReportScheduler(
            UserRepository userRepository,
            WhatsAppReportService reportService,
            NotificationService notificationService,
            LocalizedMessageService messageService,
            @Value("${economizaai.notifications.whatsapp.weekly-report.enabled:false}") boolean enabled) {
        this.userRepository = userRepository;
        this.reportService = reportService;
        this.notificationService = notificationService;
        this.messageService = messageService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${economizaai.notifications.whatsapp.weekly-report.cron:0 0 10 ? * MON}", zone = "America/Sao_Paulo")
    public void sendWeeklyReports() {
        if (!enabled) return;
        var candidates = userRepository.findWeeklyReportCandidates();
        var sent = 0;
        for (var user : candidates) {
            if (sendReportIsolated(user)) sent++;
        }
        log.info("weekly_report.done candidates={} sent={}", candidates.size(), sent);
    }

    /** One user's failure never stops the run. Returns whether a report was handed to the channels. */
    private boolean sendReportIsolated(User user) {
        try {
            var report = reportService.buildWeeklyReportNotice(user);
            if (!report.hasPurchases()) return false;
            var title = messageService.translate("notification.weekly_report.title",
                    LocalizedMessageService.toLocale(user.getLocale()));
            var notification = notificationService.notify(new NotificationPayload(user, NotificationType.WEEKLY_REPORT,
                    title, report.body(), Map.of(WhatsAppDispatcher.EXTRA_ONE_LINE_TEXT, report.summaryLine())));
            return notification != null;
        } catch (RuntimeException reportFailure) {
            log.warn("weekly_report.failed user={} reason={}",
                    LogMasker.email(user.getEmail()), reportFailure.getClass().getSimpleName());
            return false;
        }
    }
}
