package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Replenishment / "about to run out" reminders (STOCKOUT rules).
 *
 * <p>For each active STOCKOUT rule we read the household's confirmed purchase
 * history of the product, estimate the buying cadence (average days between
 * purchases), project the next runout from the last purchase, and notify when
 * we're within the rule's lead-time window. A per-rule cooldown prevents
 * nagging until the user buys again (which pushes the projection forward).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplenishmentService {

    private static final int DEFAULT_LEAD_TIME_DAYS = 3;
    private static final Duration COOLDOWN = Duration.ofDays(7);
    private static final int MIN_PURCHASES = 2;

    private final NotificationRuleRepository ruleRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;

    @Scheduled(fixedDelayString = "${economizaai.notifications.replenishment.interval-ms:21600000}",
            initialDelayString = "${economizaai.notifications.replenishment.initial-delay-ms:60000}")
    @Transactional
    public void run() {
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.STOCKOUT);
        if (rules.isEmpty()) return;
        var now = LocalDateTime.now();
        var fired = new ArrayList<NotificationRule>();
        for (var rule : rules) {
            if (inCooldown(rule, now) || rule.getProduct() == null || rule.getUser().getHousehold() == null) continue;
            if (evaluate(rule, now)) {
                rule.setLastFiredAt(now);
                fired.add(rule);
            }
        }
        if (!fired.isEmpty()) ruleRepository.saveAll(fired);
        log.info("replenishment.run done rules={} fired={}", rules.size(), fired.size());
    }

    private boolean evaluate(NotificationRule rule, LocalDateTime now) {
        var householdId = rule.getUser().getHousehold().getId();
        var history = receiptItemRepository.findHouseholdHistoryForProduct(rule.getProduct().getId(), householdId);
        var dates = history.stream()
                .map(ReceiptItem::getReceipt)
                .map(receipt -> receipt.getIssuedAt())
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
        if (dates.size() < MIN_PURCHASES) return false;

        var first = dates.get(0);
        var last = dates.get(dates.size() - 1);
        var spanDays = ChronoUnit.DAYS.between(first, last);
        if (spanDays <= 0) return false;
        var avgIntervalDays = (double) spanDays / (dates.size() - 1);
        if (avgIntervalDays <= 0) return false;

        var leadTime = rule.getLeadTimeDays() != null ? rule.getLeadTimeDays() : DEFAULT_LEAD_TIME_DAYS;
        var predictedRunout = last.plusDays((long) Math.ceil(avgIntervalDays));
        var notifyFrom = predictedRunout.minusDays(leadTime);
        if (now.isBefore(notifyFrom)) return false;

        notify(rule, Math.round(avgIntervalDays), last, predictedRunout);
        return true;
    }

    private void notify(NotificationRule rule, long avgIntervalDays, LocalDateTime lastPurchase, LocalDateTime predictedRunout) {
        var productName = rule.getProduct().getNormalizedName();
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        var title = messageService.translate("notification.replenishment.title", locale, productName);
        var body = messageService.translate("notification.replenishment.body", locale,
                productName, String.valueOf(avgIntervalDays), lastPurchase.toLocalDate().toString());
        notificationService.notify(new NotificationPayload(
                rule.getUser(), NotificationType.STOCKOUT, title, body,
                Map.of(
                        "ruleId", rule.getId().toString(),
                        "productId", rule.getProduct().getId().toString(),
                        "avgIntervalDays", avgIntervalDays,
                        "predictedRunout", predictedRunout.toLocalDate().toString())));
        log.info("replenishment.fired user={} product={} avgInterval={}d",
                LogMasker.email(rule.getUser().getEmail()), rule.getProduct().getId(), avgIntervalDays);
    }

    private boolean inCooldown(NotificationRule rule, LocalDateTime now) {
        return rule.getLastFiredAt() != null && rule.getLastFiredAt().isAfter(now.minus(COOLDOWN));
    }
}
