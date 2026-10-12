package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Map;

/**
 * Savings digests (DIGEST default): weekly (Mondays, last 7 days) and monthly
 * (1st of the month, previous calendar month). Both reuse the same DIGEST
 * preference toggle; users with no activity in the window are skipped to avoid
 * empty noise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestService {

    private static final int WINDOW_DAYS = 7;

    private final NotificationRuleRepository ruleRepository;
    private final ReceiptRepository receiptRepository;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;

    /**
     * Deliberately NOT {@code @Transactional}: {@code notificationService.notify(...)} dispatches over
     * the network (Expo push / SMTP) and a transaction spanning the loop would pin a Hikari connection
     * across every send, starving the pool under load. The counts read here are plain repository calls
     * (each in its own short tx) and there is no post-send write to protect.
     */
    @Scheduled(cron = "${economizaai.notifications.digest.cron:0 0 8 * * MON}",
            zone = "${economizaai.notifications.digest.zone:America/Sao_Paulo}")
    public void run() {
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST);
        if (rules.isEmpty()) return;
        var since = BrazilClock.nowDateTime().minusDays(WINDOW_DAYS);
        var sent = 0;
        for (var rule : rules) {
            var household = rule.getUser().getHousehold();
            if (household == null) continue;
            var receipts = receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtAfter(
                    household.getId(), ReceiptStatus.CONFIRMED, since);
            if (receipts == 0) continue;
            var spend = receiptRepository.sumConfirmedTotalSince(household.getId(), since);
            var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
            notificationService.notify(new NotificationPayload(
                    rule.getUser(), NotificationType.DIGEST,
                    messageService.translate("notification.digest.title", locale),
                    messageService.translate("notification.digest.body", locale,
                            String.valueOf(receipts), String.valueOf(spend)),
                    Map.of("receipts", receipts, "spend", spend, "windowDays", WINDOW_DAYS)));
            sent++;
        }
        log.info("digest.run done rules={} sent={}", rules.size(), sent);
    }

    /**
     * Monthly rollup: every 1st at 08:00 (Brasília) about the PREVIOUS calendar
     * month. Same transaction posture as {@link #run()}.
     */
    @Scheduled(cron = "${economizaai.notifications.digest.monthly-cron:0 0 8 1 * *}",
            zone = "${economizaai.notifications.digest.zone:America/Sao_Paulo}")
    public void runMonthly() {
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.DIGEST);
        if (rules.isEmpty()) return;
        var monthStart = BrazilClock.today().withDayOfMonth(1).minusMonths(1);
        var from = monthStart.atStartOfDay();
        var to = monthStart.plusMonths(1).atStartOfDay();
        var sent = 0;
        for (var rule : rules) {
            if (sendMonthlyDigest(rule, from, to, monthStart.getMonth())) sent++;
        }
        log.info("digest.monthly done rules={} sent={} month={}", rules.size(), sent, monthStart.getMonth());
    }

    private boolean sendMonthlyDigest(NotificationRule rule, LocalDateTime from,
                                      LocalDateTime to, Month month) {
        var household = rule.getUser().getHousehold();
        if (household == null) return false;
        var receipts = receiptRepository.countByHouseholdIdAndStatusAndConfirmedAtGreaterThanEqualAndConfirmedAtBefore(
                household.getId(), ReceiptStatus.CONFIRMED, from, to);
        if (receipts == 0) return false;
        var spend = receiptRepository.sumConfirmedTotalBetween(household.getId(), from, to);
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        var monthName = month.getDisplayName(TextStyle.FULL, locale);
        notificationService.notify(new NotificationPayload(
                rule.getUser(), NotificationType.DIGEST,
                messageService.translate("notification.digest.monthly.title", locale, monthName),
                messageService.translate("notification.digest.monthly.body", locale,
                        monthName, String.valueOf(receipts), String.valueOf(spend)),
                Map.of("receipts", receipts, "spend", spend, "month", month.getValue())));
        return true;
    }
}
