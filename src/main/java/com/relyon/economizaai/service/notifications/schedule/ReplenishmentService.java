package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import com.relyon.economizaai.service.notifications.NotificationTextFormatter;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replenishment / "about to run out" reminders (STOCKOUT rules).
 *
 * <p>The push says what the forecast screen shows: a product is due when
 * {@link ConsumptionIntelligenceService#predict} classifies it as running low or
 * ran out — the same prediction, so "Já comprei" (manual purchase) and "Adiar"
 * (snooze) silence the reminder exactly as they clear the screen.
 *
 * <p>Reminders for one purchase are spaced by {@link #COOLDOWN} and stop after
 * {@link #MAX_REMINDERS_PER_PURCHASE}: someone who never answers is not nagged
 * forever. A new purchase (scanned or logged) starts the count again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplenishmentService {

    static final int MAX_REMINDERS_PER_PURCHASE = 3;
    private static final Duration COOLDOWN = Duration.ofDays(7);

    private final NotificationRuleRepository ruleRepository;
    private final ConsumptionIntelligenceService consumptionService;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;
    private final NotificationSettingsService settingsService;

    // Self-reference so the per-rule @Transactional persist goes through the Spring
    // proxy (a direct call would bypass it). Defaults to `this` for plain unit tests;
    // Spring replaces it with the lazy proxy at runtime.
    @Lazy
    @Autowired
    private ReplenishmentService self = this;

    /**
     * Never holds a DB transaction across {@code notificationService.notify(...)} — that dispatch is an
     * outbound HTTP/SMTP call and a transaction spanning the loop would pin a Hikari connection for the
     * whole round-trip, starving the pool under load. Predictions and the send run untransacted; only
     * the reminder bookkeeping is persisted afterwards, in a short transaction.
     */
    @Scheduled(cron = "${economizaai.notifications.replenishment.cron:0 0 10 * * *}",
            zone = "America/Sao_Paulo")
    public void run() {
        if (!settingsService.isEnabled(NotificationJob.REPLENISHMENT)) return;
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.STOCKOUT);
        if (rules.isEmpty()) return;
        var now = LocalDateTime.now();
        var fired = 0;
        for (var userRules : rulesByUser(rules).values()) {
            try {
                fired += remindUser(userRules, now);
            } catch (RuntimeException ex) {
                log.warn("replenishment.user_failed user={} reason={}",
                        LogMasker.email(userRules.get(0).getUser().getEmail()), ex.getMessage());
            }
        }
        log.info("replenishment.run done rules={} fired={}", rules.size(), fired);
    }

    private Map<UUID, List<NotificationRule>> rulesByUser(List<NotificationRule> rules) {
        return rules.stream()
                .filter(rule -> rule.getProduct() != null && rule.getUser().getHousehold() != null)
                .collect(Collectors.groupingBy(rule -> rule.getUser().getId(),
                        LinkedHashMap::new, Collectors.toList()));
    }

    /** One forecast per user, shared by all of that user's rules. */
    private int remindUser(List<NotificationRule> userRules, LocalDateTime now) {
        var predictionsByProduct = consumptionService.predict(userRules.get(0).getUser()).stream()
                .collect(Collectors.toMap(ConsumptionPredictionResponse::productId, Function.identity(),
                        (first, duplicate) -> first));
        var fired = 0;
        for (var rule : userRules) {
            var prediction = predictionsByProduct.get(rule.getProduct().getId());
            if (prediction == null || !isDue(prediction) || !mayRemind(rule, prediction, now)) continue;
            notify(rule, prediction);
            self.markReminded(rule, prediction.lastPurchaseDate(), now);
            fired++;
        }
        return fired;
    }

    /** Due = what the forecast screen lists as "acabando" or "acabou". Snoozed products have no prediction. */
    private boolean isDue(ConsumptionPredictionResponse prediction) {
        return prediction.status() != ConsumptionPredictionResponse.Status.OK;
    }

    private boolean mayRemind(NotificationRule rule, ConsumptionPredictionResponse prediction, LocalDateTime now) {
        if (remindersSince(rule, prediction.lastPurchaseDate()) >= MAX_REMINDERS_PER_PURCHASE) {
            log.debug("replenishment.silenced rule={} reason=max_reminders", rule.getId());
            return false;
        }
        return !inCooldown(rule, now);
    }

    /** Reminders already sent for THIS purchase; a newer purchase starts from zero. */
    private int remindersSince(NotificationRule rule, LocalDate lastPurchaseDate) {
        return lastPurchaseDate.equals(rule.getReminderPurchaseDate()) ? rule.getReminderCount() : 0;
    }

    /** Persist the reminder bookkeeping (count per purchase + cooldown) in a short transaction, after the send. */
    @Transactional
    public void markReminded(NotificationRule rule, LocalDate lastPurchaseDate, LocalDateTime firedAt) {
        rule.setReminderCount(remindersSince(rule, lastPurchaseDate) + 1);
        rule.setReminderPurchaseDate(lastPurchaseDate);
        rule.setLastFiredAt(firedAt);
        ruleRepository.save(rule);
    }

    private void notify(NotificationRule rule, ConsumptionPredictionResponse prediction) {
        var productName = rule.getProduct().getNormalizedName();
        var averageIntervalDays = prediction.averageIntervalDays().setScale(0, RoundingMode.HALF_UP).longValue();
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        var title = messageService.translate("notification.replenishment.title", locale, productName);
        var body = messageService.translate("notification.replenishment.body", locale,
                productName, String.valueOf(averageIntervalDays),
                NotificationTextFormatter.dayMonth(prediction.lastPurchaseDate()));
        notificationService.notify(new NotificationPayload(
                rule.getUser(), NotificationType.STOCKOUT, title, body,
                Map.of(
                        "ruleId", rule.getId().toString(),
                        "productId", rule.getProduct().getId().toString(),
                        "avgIntervalDays", averageIntervalDays,
                        "predictedRunout", prediction.predictedNextPurchaseDate().toString())));
        log.info("replenishment.fired user={} product={} avgInterval={}d status={}",
                LogMasker.email(rule.getUser().getEmail()), rule.getProduct().getId(),
                averageIntervalDays, prediction.status());
    }

    private boolean inCooldown(NotificationRule rule, LocalDateTime now) {
        return rule.getLastFiredAt() != null && rule.getLastFiredAt().isAfter(now.minus(COOLDOWN));
    }
}
