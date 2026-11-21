package com.relyon.economizaai.service.notifications.schedule;
import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.service.LocalizedMessageService;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import com.relyon.economizaai.service.notifications.NotificationTextFormatter;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monthly budget alerts (BUDGET rules). Notifies every member once per calendar month
 * when the household's spend in the current month reaches the household's limit. The
 * limit is one per household: when members saved different ones, the most recently
 * changed is the one in force. "Spend" is the dashboard's figure — item totals by
 * PURCHASE date — so scanning old receipts today never counts against this month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetMonitorService {

    private final NotificationRuleRepository ruleRepository;
    private final InsightsRepository insightsRepository;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;
    private final NotificationSettingsService settingsService;
    private final UserRepository userRepository;

    /** Hours of the day (Brasília) the household budgets are checked. */
    @Getter @Setter
    @Value("${economizaai.notifications.budget.check-hours:9,18}")
    private volatile List<Integer> checkHours = List.of(9, 18);

    private final HourlySendGate sendGate = new HourlySendGate();

    // Self-reference so the per-rule @Transactional persist goes through the Spring
    // proxy (a direct call would bypass it). Defaults to `this` for plain unit tests;
    // Spring replaces it with the lazy proxy at runtime.
    @Lazy
    @Autowired
    private BudgetMonitorService self = this;

    @Scheduled(cron = HourlySendGate.EVERY_FULL_HOUR, zone = HourlySendGate.ZONE)
    public void runAtCheckHours() {
        runIfCheckHour(BrazilClock.now());
    }

    void runIfCheckHour(ZonedDateTime now) {
        if (sendGate.claimDailyAtAnyOf(now, checkHours)) run();
    }

    /**
     * Never holds a DB transaction across {@code notificationService.notify(...)} — that dispatch is
     * an outbound HTTP/SMTP call and a transaction spanning it would pin a Hikari connection for the
     * whole round-trip and, under load, starve the pool. Candidate reads and the send run untransacted;
     * only the fired-timestamp is persisted afterwards, in a short transaction (same split as
     * {@code MarketLocationService.geocodeOne}/{@code persistGeocodeResult}).
     */
    public void run() {
        if (!settingsService.isEnabled(NotificationJob.BUDGET_ALERT)) return;
        var rules = ruleRepository.findActiveByTypeFetchUserAndProduct(NotificationType.BUDGET);
        if (rules.isEmpty()) return;
        var now = BrazilClock.nowDateTime();
        var currentMonth = BrazilClock.currentYearMonth();
        var startOfMonth = currentMonth.atDay(1).atStartOfDay();
        var endOfMonth = currentMonth.plusMonths(1).atDay(1).atStartOfDay().minusNanos(1);
        var fired = 0;
        for (var householdRules : rulesByHousehold(rules).entrySet()) {
            var householdId = householdRules.getKey();
            if (householdRules.getValue().stream().anyMatch(rule -> firedThisMonth(rule, now))) continue;
            var limitInForce = mostRecentlyChanged(householdRules.getValue());
            var spend = insightsRepository.totalSpendAllSegments(householdId, startOfMonth, endOfMonth);
            if (spend.compareTo(limitInForce.getThresholdPrice()) < 0) continue;
            userRepository.findAllByHouseholdId(householdId)
                    .forEach(member -> notify(limitInForce, member, spend));
            self.markFired(limitInForce, now);
            fired++;
        }
        log.info("budget.run done rules={} fired={}", rules.size(), fired);
    }

    /** Usable budget rules grouped by the household of the member who saved them. */
    private Map<UUID, List<NotificationRule>> rulesByHousehold(List<NotificationRule> rules) {
        return rules.stream()
                .filter(rule -> rule.getThresholdPrice() != null && rule.getUser().getHousehold() != null)
                .collect(Collectors.groupingBy(rule -> rule.getUser().getHousehold().getId()));
    }

    private NotificationRule mostRecentlyChanged(List<NotificationRule> householdRules) {
        return householdRules.stream()
                .max(Comparator.comparing(NotificationRule::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
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

    private void notify(NotificationRule rule, User member, BigDecimal spend) {
        var locale = LocalizedMessageService.toLocale(member.getLocale());
        var title = messageService.translate("notification.budget.title", locale);
        var monthName = BrazilClock.today().getMonth().getDisplayName(TextStyle.FULL, locale);
        var body = messageService.translate("notification.budget.body", locale,
                NotificationTextFormatter.amount(spend), monthName,
                NotificationTextFormatter.amount(rule.getThresholdPrice()));
        notificationService.notify(new NotificationPayload(
                member, NotificationType.BUDGET, title, body,
                Map.of(
                        "ruleId", rule.getId().toString(),
                        "spend", spend,
                        "threshold", rule.getThresholdPrice())));
        log.info("budget.fired user={} spend={} threshold={}",
                LogMasker.email(member.getEmail()), spend, rule.getThresholdPrice());
    }
}
