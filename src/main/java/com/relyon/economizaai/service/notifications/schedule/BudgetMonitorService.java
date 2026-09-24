package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Map;

/**
 * Monthly budget alerts (BUDGET rules). Notifies once per calendar month when a
 * household's confirmed spend in the current month reaches the user's threshold.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetMonitorService {

    private final NotificationRuleRepository ruleRepository;
    private final ReceiptRepository receiptRepository;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;

    // Self-reference so the per-rule @Transactional persist goes through the Spring
    // proxy (a direct call would bypass it). Defaults to `this` for plain unit tests;
    // Spring replaces it with the lazy proxy at runtime.
    @Lazy
    @Autowired
    private BudgetMonitorService self = this;

    /**
     * Never holds a DB transaction across {@code notificationService.notify(...)} — that dispatch is
     * an outbound HTTP/SMTP call and a transaction spanning it would pin a Hikari connection for the
     * whole round-trip and, under load, starve the pool. Candidate reads and the send run untransacted;
     * only the fired-timestamp is persisted afterwards, in a short transaction (same split as
     * {@code MarketLocationService.geocodeOne}/{@code persistGeocodeResult}).
     */
    @Scheduled(fixedDelayString = "${economizaai.notifications.budget.interval-ms:21600000}",
            initialDelayString = "${economizaai.notifications.budget.initial-delay-ms:90000}")
    public void run() {
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET);
        if (rules.isEmpty()) return;
        var now = LocalDateTime.now();
        var startOfMonth = YearMonth.now().atDay(1).atStartOfDay();
        var fired = 0;
        for (var rule : rules) {
            if (rule.getThresholdPrice() == null || rule.getUser().getHousehold() == null) continue;
            if (firedThisMonth(rule, now)) continue;
            var spend = receiptRepository.sumConfirmedTotalSince(rule.getUser().getHousehold().getId(), startOfMonth);
            if (spend.compareTo(rule.getThresholdPrice()) < 0) continue;
            notify(rule, spend);
            self.markFired(rule, now);
            fired++;
        }
        log.info("budget.run done rules={} fired={}", rules.size(), fired);
    }

    /** Persist the once-per-month guard timestamp in a short transaction, after the send. */
    @Transactional
    public void markFired(NotificationRule rule, LocalDateTime firedAt) {
        rule.setLastFiredAt(firedAt);
        ruleRepository.save(rule);
    }

    private boolean firedThisMonth(NotificationRule rule, LocalDateTime now) {
        var last = rule.getLastFiredAt();
        return last != null && YearMonth.from(last).equals(YearMonth.from(now));
    }

    private void notify(NotificationRule rule, BigDecimal spend) {
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        var title = messageService.translate("notification.budget.title", locale);
        var monthName = LocalDate.now().getMonth().getDisplayName(TextStyle.FULL, locale);
        var body = messageService.translate("notification.budget.body", locale,
                spend.toString(), monthName, rule.getThresholdPrice().toString());
        notificationService.notify(new NotificationPayload(
                rule.getUser(), NotificationType.BUDGET, title, body,
                Map.of(
                        "ruleId", rule.getId().toString(),
                        "spend", spend,
                        "threshold", rule.getThresholdPrice())));
        log.info("budget.fired user={} spend={} threshold={}",
                LogMasker.email(rule.getUser().getEmail()), spend, rule.getThresholdPrice());
    }
}
