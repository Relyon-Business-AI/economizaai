package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.enums.NotificationJob;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.notifications.NotificationSettingsService;
import com.relyon.economizaai.service.notifications.NotificationTextFormatter;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;

/**
 * Resumos de gastos (DIGEST): semanal e mensal. A agenda (dia/hora), o
 * liga/desliga e os textos vêm de {@code notification_system_settings}
 * (editável no dashboard) — um tick HORÁRIO pergunta ao
 * {@link NotificationSettingsService} se o disparo do dia é agora; o claim
 * atômico é feito ANTES do envio e garante no máximo um disparo por dia mesmo
 * com sobreposição, restart ou 2 instâncias.
 *
 * <p>O envio sai da thread do agendador: roda no executor de notificações, em
 * páginas de regras, com UMA consulta agrupada por página para saber quais
 * casas tiveram compras. O gasto é o mesmo do painel — soma dos itens (sem os
 * excluídos / "não é meu") pela DATA DA COMPRA — então quem escaneia hoje notas
 * de meses atrás não ouve que gastou tudo agora. Casas sem compra na janela são
 * puladas (sem ruído vazio).
 */
@Slf4j
@Service
public class DigestService {

    private static final int WINDOW_DAYS = 7;
    private static final int PAGE_SIZE = 200;
    /** Sorts before every real id — the starting point of the keyset pagination. */
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);

    private final NotificationRuleRepository ruleRepository;
    private final InsightsRepository insightsRepository;
    private final NotificationService notificationService;
    private final NotificationSettingsService settingsService;
    private final Executor dispatchExecutor;

    public DigestService(NotificationRuleRepository ruleRepository,
                         InsightsRepository insightsRepository,
                         NotificationService notificationService,
                         NotificationSettingsService settingsService,
                         @Qualifier(AsyncConfig.NOTIFICATION_DISPATCH_EXECUTOR) Executor dispatchExecutor) {
        this.ruleRepository = ruleRepository;
        this.insightsRepository = insightsRepository;
        this.notificationService = notificationService;
        this.settingsService = settingsService;
        this.dispatchExecutor = dispatchExecutor;
    }

    @Scheduled(cron = "${economizaai.notifications.digest.tick-cron:0 0 * * * *}",
            zone = "${economizaai.notifications.digest.zone:America/Sao_Paulo}")
    public void tick() {
        var now = BrazilClock.now();
        if (settingsService.claimDueDaily(NotificationJob.WEEKLY_DIGEST, now)) runOffSchedulerThread(this::run);
        if (settingsService.claimDueDaily(NotificationJob.MONTHLY_DIGEST, now)) runOffSchedulerThread(this::runMonthly);
    }

    /** The day's run is already claimed, so a full pool must not lose it: fall back to running inline. */
    private void runOffSchedulerThread(Runnable digestRun) {
        try {
            dispatchExecutor.execute(digestRun);
        } catch (RejectedExecutionException rejection) {
            log.warn("digest.executor_rejected reason=pool_full fallback=inline");
            digestRun.run();
        }
    }

    /** Resumo semanal: compras dos últimos {@value WINDOW_DAYS} dias. */
    public void run() {
        var to = BrazilClock.nowDateTime();
        var from = to.minusDays(WINDOW_DAYS);
        var sent = sendToHouseholdsWithPurchases(from, to, this::weeklyPayload);
        log.info("digest.run done sent={}", sent);
    }

    /** Resumo mensal: compras do mês civil anterior completo. */
    public void runMonthly() {
        var monthStart = BrazilClock.today().withDayOfMonth(1).minusMonths(1);
        var from = monthStart.atStartOfDay();
        var to = monthStart.plusMonths(1).atStartOfDay().minusNanos(1);
        var month = monthStart.getMonth();
        var sent = sendToHouseholdsWithPurchases(from, to,
                (rule, householdSpend) -> monthlyPayload(rule, householdSpend, month));
        log.info("digest.monthly done sent={} month={}", sent, month);
    }

    /**
     * Walks the active DIGEST rules page by page. Deliberately NOT {@code @Transactional}:
     * the sends are outbound calls, and a transaction around the loop would hold a
     * connection across every one of them.
     */
    private int sendToHouseholdsWithPurchases(LocalDateTime from, LocalDateTime to,
                                              BiFunction<NotificationRule, HouseholdSpend, NotificationPayload> payloadFor) {
        var sent = 0;
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var rules = ruleRepository.findActivePageByTypeFetchUser(
                    NotificationType.DIGEST, afterId, PageRequest.of(0, PAGE_SIZE));
            if (rules.isEmpty()) break;
            var spendByHousehold = spendByHousehold(rules, from, to);
            for (var rule : rules) {
                var householdSpend = spendByHousehold.get(householdIdOf(rule));
                if (householdSpend == null) continue;
                if (sendIsolated(payloadFor.apply(rule, householdSpend))) sent++;
            }
            afterId = rules.get(rules.size() - 1).getId();
        }
        return sent;
    }

    private Map<UUID, HouseholdSpend> spendByHousehold(List<NotificationRule> rules,
                                                       LocalDateTime from, LocalDateTime to) {
        var householdIds = rules.stream().map(this::householdIdOf).filter(Objects::nonNull).distinct().toList();
        var spendByHousehold = new HashMap<UUID, HouseholdSpend>();
        if (householdIds.isEmpty()) return spendByHousehold;
        for (var row : insightsRepository.spendByHouseholds(householdIds, from, to)) {
            var receiptCount = ((Number) row[2]).longValue();
            if (receiptCount == 0) continue;
            spendByHousehold.put((UUID) row[0], new HouseholdSpend(toMoney(row[1]), receiptCount));
        }
        return spendByHousehold;
    }

    private static BigDecimal toMoney(Object aggregate) {
        var value = aggregate instanceof BigDecimal decimal ? decimal : new BigDecimal(aggregate.toString());
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private UUID householdIdOf(NotificationRule rule) {
        var household = rule.getUser().getHousehold();
        return household == null ? null : household.getId();
    }

    /** One recipient's failure is logged and skipped — it never aborts the batch. */
    private boolean sendIsolated(NotificationPayload payload) {
        try {
            notificationService.notify(payload);
            return true;
        } catch (RuntimeException ex) {
            log.warn("digest.user_failed user={} reason={}",
                    LogMasker.email(payload.user().getEmail()), ex.getMessage());
            return false;
        }
    }

    private NotificationPayload weeklyPayload(NotificationRule rule, HouseholdSpend householdSpend) {
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        return new NotificationPayload(
                rule.getUser(), NotificationType.DIGEST,
                settingsService.resolveTitle(NotificationJob.WEEKLY_DIGEST, locale,
                        "notification.digest.title"),
                settingsService.resolveBody(NotificationJob.WEEKLY_DIGEST, locale,
                        "notification.digest.body", String.valueOf(householdSpend.receiptCount()),
                        NotificationTextFormatter.amount(householdSpend.total())),
                Map.of("receipts", householdSpend.receiptCount(), "spend", householdSpend.total(),
                        "windowDays", WINDOW_DAYS));
    }

    private NotificationPayload monthlyPayload(NotificationRule rule, HouseholdSpend householdSpend, Month month) {
        var locale = LocalizedMessageService.toLocale(rule.getUser().getLocale());
        var monthName = month.getDisplayName(TextStyle.FULL, locale);
        return new NotificationPayload(
                rule.getUser(), NotificationType.DIGEST,
                settingsService.resolveTitle(NotificationJob.MONTHLY_DIGEST, locale,
                        "notification.digest.monthly.title", monthName),
                settingsService.resolveBody(NotificationJob.MONTHLY_DIGEST, locale,
                        "notification.digest.monthly.body",
                        monthName, String.valueOf(householdSpend.receiptCount()),
                        NotificationTextFormatter.amount(householdSpend.total())),
                Map.of("receipts", householdSpend.receiptCount(), "spend", householdSpend.total(),
                        "month", month.getValue()));
    }

    /** A household's purchases in the window: item total and how many receipts. */
    private record HouseholdSpend(BigDecimal total, long receiptCount) {}
}
