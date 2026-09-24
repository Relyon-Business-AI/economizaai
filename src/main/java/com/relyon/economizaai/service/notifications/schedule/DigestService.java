package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.service.LocalizedMessageService;

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
import java.util.Map;

/**
 * Weekly savings digest (DIGEST default). Sends each opted-in user a summary of
 * their household activity over the past week. Users with no activity are
 * skipped to avoid empty noise.
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
        var since = LocalDateTime.now().minusDays(WINDOW_DAYS);
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
}
