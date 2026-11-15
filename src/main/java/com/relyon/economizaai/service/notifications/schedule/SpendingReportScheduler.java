package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.WhatsAppDispatcher;
import com.relyon.economizaai.service.notifications.WhatsAppReportService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Proactive spending report (the WEEKLY_REPORT notice) to everyone who has one of its options
 * on: the weekly run reaches who chose the weekly frequency, the monthly run who chose the
 * monthly one — a user may have both. INERT by default
 * (economizaai.notifications.whatsapp.weekly-report.enabled=false, one flag for both runs) — it
 * ships built but off. Each report goes through {@link NotificationService}, which sends it on
 * the channels the user has on: e-mail (the full text) and WhatsApp (the approved template,
 * whose single {{1}} variable carries a one-line summary; skipped while no report template SID
 * is configured). A period without purchases sends nothing. Per-user failures are logged, never
 * fatal.
 *
 * <p>Deliberately not transactional: each report is read in its own short transaction and
 * the dispatch (SMTP/Twilio) runs outside any.
 */
@Slf4j
@Service
public class SpendingReportScheduler {

    private final UserRepository userRepository;
    private final WhatsAppReportService reportService;
    private final NotificationService notificationService;
    private final boolean enabled;

    public SpendingReportScheduler(
            UserRepository userRepository,
            WhatsAppReportService reportService,
            NotificationService notificationService,
            @Value("${economizaai.notifications.whatsapp.weekly-report.enabled:false}") boolean enabled) {
        this.userRepository = userRepository;
        this.reportService = reportService;
        this.notificationService = notificationService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${economizaai.notifications.whatsapp.weekly-report.cron:0 0 10 ? * MON}", zone = "America/Sao_Paulo")
    public void sendWeeklyReports() {
        if (!enabled) return;
        var candidates = userRepository.findWeeklyReportCandidates();
        var sent = sendReports(candidates, SpendingReportFrequency.WEEKLY);
        log.info("weekly_report.done candidates={} sent={}", candidates.size(), sent);
    }

    @Scheduled(cron = "${economizaai.notifications.whatsapp.monthly-report.cron:0 0 10 1 * ?}", zone = "America/Sao_Paulo")
    public void sendMonthlyReports() {
        if (!enabled) return;
        var candidates = userRepository.findMonthlyReportCandidates();
        var sent = sendReports(candidates, SpendingReportFrequency.MONTHLY);
        log.info("monthly_report.done candidates={} sent={}", candidates.size(), sent);
    }

    private int sendReports(List<User> candidates, SpendingReportFrequency frequency) {
        var sent = 0;
        for (var user : candidates) {
            if (sendReportIsolated(user, frequency)) sent++;
        }
        return sent;
    }

    /** One user's failure never stops the run. Returns whether a report was handed to the channels. */
    private boolean sendReportIsolated(User user, SpendingReportFrequency frequency) {
        try {
            var report = reportService.buildSpendingReportNotice(user, frequency);
            if (!report.hasPurchases()) return false;
            var notification = notificationService.notify(new NotificationPayload(user, typeOf(frequency),
                    report.title(), report.body(), Map.of(WhatsAppDispatcher.EXTRA_ONE_LINE_TEXT, report.summaryLine())));
            return notification != null;
        } catch (RuntimeException reportFailure) {
            log.warn("spending_report.failed frequency={} user={} reason={}", frequency,
                    LogMasker.email(user.getEmail()), reportFailure.getClass().getSimpleName());
            return false;
        }
    }

    private static NotificationType typeOf(SpendingReportFrequency frequency) {
        return switch (frequency) {
            case WEEKLY -> NotificationType.WEEKLY_REPORT;
            case MONTHLY -> NotificationType.MONTHLY_REPORT;
        };
    }
}
