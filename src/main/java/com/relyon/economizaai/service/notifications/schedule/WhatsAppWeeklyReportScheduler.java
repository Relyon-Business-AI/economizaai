package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.WhatsAppReportService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Proactive weekly WhatsApp report to opted-in users. INERT by default
 * (economizaai.notifications.whatsapp.weekly-report.enabled=false) — it ships built but off,
 * and also no-ops until a report template SID is configured. Proactive (business-initiated)
 * WhatsApp needs an approved template; the single {{1}} variable carries a one-line summary
 * (template variables can't hold newlines). Per-user failures are logged, never fatal.
 */
@Slf4j
@Service
public class WhatsAppWeeklyReportScheduler {

    private final UserRepository userRepository;
    private final WhatsAppReportService reportService;
    private final TwilioMessageClient twilioClient;
    private final boolean enabled;
    private final String reportTemplateSid;

    public WhatsAppWeeklyReportScheduler(
            UserRepository userRepository,
            WhatsAppReportService reportService,
            TwilioMessageClient twilioClient,
            @Value("${economizaai.notifications.whatsapp.weekly-report.enabled:false}") boolean enabled,
            @Value("${economizaai.notifications.twilio.report-template-sid:}") String reportTemplateSid) {
        this.userRepository = userRepository;
        this.reportService = reportService;
        this.twilioClient = twilioClient;
        this.enabled = enabled;
        this.reportTemplateSid = reportTemplateSid;
    }

    @Scheduled(cron = "${economizaai.notifications.whatsapp.weekly-report.cron:0 0 10 ? * MON}", zone = "America/Sao_Paulo")
    public void sendWeeklyReports() {
        if (!enabled || reportTemplateSid == null || reportTemplateSid.isBlank() || !twilioClient.isConfigured(true)) {
            return;
        }
        var candidates = userRepository.findWhatsAppWeeklyReportCandidates();
        var sent = 0;
        for (var user : candidates) {
            try {
                var summary = reportService.buildWeeklySummaryLine(user);
                var variables = "{\"1\":\"" + summary.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
                twilioClient.sendWhatsAppTemplate(user.getWhatsAppNumber(), reportTemplateSid, variables);
                sent++;
            } catch (RuntimeException sendFailure) {
                log.warn("whatsapp.weekly_report.failed user={} reason={}",
                        user.getId() == null ? "?" : user.getId().toString().substring(0, 8),
                        sendFailure.getClass().getSimpleName());
            }
        }
        log.info("whatsapp.weekly_report.done candidates={} sent={}", candidates.size(), sent);
    }
}
