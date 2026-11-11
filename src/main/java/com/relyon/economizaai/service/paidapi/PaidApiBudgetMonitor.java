package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.config.PaidApiGuardProperties;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository.ServiceSpend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

/**
 * Warns the admin when a service's daily budget reaches 80% and 100%.
 *
 * <p>Design choice: a short scheduled check (every minute, ONE grouped query over today's
 * ledger) instead of evaluating on each ledger write — the hot ingestion path gets no
 * extra query and no coupling to alert delivery, at the price of up to a minute of delay.
 * The "once per (service, threshold, day)" memory and the delivery guarantee live in
 * {@link CostAlertService}. The day is the UTC day, the same one the budgets reset on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaidApiBudgetMonitor {

    static final String OVERALL_ALERT_KEY = "BUDGET:TOTAL";
    private static final String REAIS = "R$";

    private final PaidApiGuardProperties properties;
    private final PaidApiCallRepository repository;
    private final CostAlertService costAlertService;
    private Clock clock = Clock.systemUTC();

    /** Test seam — pin "today". */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${economizaai.cost-alerts.budget-check-interval-ms:60000}",
            initialDelayString = "${economizaai.cost-alerts.budget-check-initial-delay-ms:90000}")
    public void checkDailyBudgets() {
        if (!properties.isEnabled() || !costAlertService.isEnabled()) return;
        var today = LocalDate.now(clock);
        var spentCentsByService = spentTodayByService(today);
        var anyRaised = false;
        for (var service : PaidApiService.values()) {
            anyRaised |= raiseIfThresholdReached(alertKey(service), today,
                    spentCentsByService.getOrDefault(service, 0L), properties.dailyBudgetCents(service),
                    label(service), whatStops(service));
        }
        var totalSpentCents = spentCentsByService.values().stream().mapToLong(Long::longValue).sum();
        anyRaised |= raiseIfThresholdReached(OVERALL_ALERT_KEY, today, totalSpentCents,
                properties.getDailyGlobalBudgetCents(), "todos os serviços pagos",
                "todas as chamadas pagas param (a leitura grátis de notas continua)");
        if (anyRaised) {
            costAlertService.dispatchPending();
        }
    }

    static String alertKey(PaidApiService service) {
        return "BUDGET:" + service.name();
    }

    private Map<PaidApiService, Long> spentTodayByService(LocalDate today) {
        var startOfToday = OffsetDateTime.of(today.atStartOfDay(), ZoneOffset.UTC);
        var spentCentsByService = new EnumMap<PaidApiService, Long>(PaidApiService.class);
        for (ServiceSpend spend : repository.spendByService(startOfToday)) {
            spentCentsByService.put(spend.getService(), spend.getCostCents());
        }
        return spentCentsByService;
    }

    private boolean raiseIfThresholdReached(String alertKey, LocalDate today, long spentCents, int budgetCents,
                                            String serviceLabel, String whatStops) {
        var spent = reais(spentCents);
        var budget = reais(budgetCents);
        var threshold = CostAlertService.thresholdReached(spent, budget);
        if (threshold == 0) return false;
        var headline = threshold == CostAlertService.LIMIT_THRESHOLD
                ? "Economiza AI: teto diário de %s ATINGIDO (%s de %s). Parado até a virada do dia (21h de Brasília): %s."
                        .formatted(serviceLabel, CostAlertService.money(REAIS, spent),
                                CostAlertService.money(REAIS, budget), whatStops)
                : "Economiza AI: teto diário de %s em 80%% (%s de %s). Se chegar a 100%%: %s."
                        .formatted(serviceLabel, CostAlertService.money(REAIS, spent),
                                CostAlertService.money(REAIS, budget), whatStops);
        var detail = headline + "\n\nO teto zera à meia-noite UTC (21h de Brasília). "
                + "Para mudar o valor, ajuste a variável de ambiente do teto desse serviço (0 = sem teto).";
        return costAlertService.raise(alertKey, threshold, today.toString(), headline, detail);
    }

    private static BigDecimal reais(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    private static String label(PaidApiService service) {
        return switch (service) {
            case INFOSIMPLES -> "Infosimples";
            case CAPTCHA_SOLVE -> "captcha";
            case TWILIO_MESSAGE -> "WhatsApp/SMS (Twilio)";
            case LLM_ENRICH -> "IA de categorização";
            case LLM_VISION -> "nota por foto (IA)";
            case LLM_HTML_RESCUE -> "resgate de nota por IA";
        };
    }

    private static String whatStops(PaidApiService service) {
        return switch (service) {
            case INFOSIMPLES -> "as consultas pagas de nota param (CE e o resgate de portais fora do ar)";
            case CAPTCHA_SOLVE -> "as notas de estados com captcha param (estados sem captcha seguem normais)";
            case TWILIO_MESSAGE -> "SMS de verificação e WhatsApp para usuários param (avisos ao admin continuam)";
            case LLM_ENRICH -> "sugestão e correção de categoria por IA param (as notas entram normalmente)";
            case LLM_VISION -> "o envio de nota por foto para";
            case LLM_HTML_RESCUE -> "o resgate por IA de notas que o leitor não entendeu para";
        };
    }
}
