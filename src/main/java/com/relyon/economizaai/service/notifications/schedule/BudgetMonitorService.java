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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Locale;
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

    @Scheduled(fixedDelayString = "${economizaai.notifications.budget.interval-ms:21600000}",
            initialDelayString = "${economizaai.notifications.budget.initial-delay-ms:90000}")
    @Transactional
    public void run() {
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET);
        if (rules.isEmpty()) return;
        var now = LocalDateTime.now();
        var startOfMonth = YearMonth.now().atDay(1).atStartOfDay();
        var fired = new ArrayList<NotificationRule>();
        for (var rule : rules) {
            if (rule.getThresholdPrice() == null || rule.getUser().getHousehold() == null) continue;
            if (firedThisMonth(rule, now)) continue;
            var spend = receiptRepository.sumConfirmedTotalSince(rule.getUser().getHousehold().getId(), startOfMonth);
            if (spend.compareTo(rule.getThresholdPrice()) < 0) continue;
            notify(rule, spend);
            rule.setLastFiredAt(now);
            fired.add(rule);
        }
        if (!fired.isEmpty()) ruleRepository.saveAll(fired);
        log.info("budget.run done rules={} fired={}", rules.size(), fired.size());
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
