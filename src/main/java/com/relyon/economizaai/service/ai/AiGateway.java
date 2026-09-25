package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiUsageLog;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * Single entry point for every AI call in the app. Responsibilities:
 * <ul>
 *   <li><b>Gating</b> — disabled when no API key; hard daily request cap so a
 *       bug can never run up a surprise bill;</li>
 *   <li><b>Accounting</b> — logs every call (tokens + estimated USD) labeled by
 *       {@link AiActivity}, feeding the admin spend panel;</li>
 *   <li><b>Model routing</b> — cheap extractor model for high-volume extraction,
 *       stronger curator model for judgment calls.</li>
 * </ul>
 * NEVER call this inside a DB transaction — it is an outbound HTTP call and
 * would pin a Hikari connection (same rule as the SEFAZ fetches).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGateway {

    /** USD per 1M tokens (input, output) — local estimate for the spend panel. */
    private static final Map<String, double[]> PRICES_PER_MTOK = Map.of(
            "claude-haiku-4-5", new double[]{1.0, 5.0},
            "claude-sonnet-4-6", new double[]{3.0, 15.0},
            "claude-opus-4-8", new double[]{5.0, 25.0});
    private static final double[] DEFAULT_PRICE = {3.0, 15.0};

    private final AnthropicChatAdapter chatAdapter;
    private final AiUsageLogRepository usageRepository;

    @Value("${economizaai.ai.extractor-model:claude-haiku-4-5}")
    private String extractorModel;

    @Value("${economizaai.ai.curator-model:claude-sonnet-4-6}")
    private String curatorModel;

    @Value("${economizaai.ai.daily-request-cap:300}")
    private int dailyRequestCap;

    public boolean isEnabled() {
        return chatAdapter.isConfigured();
    }

    public String extractorModel() {
        return extractorModel;
    }

    public String curatorModel() {
        return curatorModel;
    }

    /**
     * Runs one AI call, enforcing the daily cap and logging usage. Throws
     * {@link AiUnavailableException} when disabled or over budget — callers
     * surface that as a clean admin-facing message instead of a stack trace.
     */
    public String complete(AiActivity activity, String model, String systemPrompt, String userPrompt, int maxTokens) {
        if (!isEnabled()) {
            throw new AiUnavailableException("IA desabilitada: ANTHROPIC_API_KEY não configurada.");
        }
        var todayStart = LocalDate.now().atStartOfDay();
        var callsToday = usageRepository.countByCreatedAtAfter(todayStart);
        if (callsToday >= dailyRequestCap) {
            throw new AiUnavailableException(
                    "Cap diário de chamadas de IA atingido (" + dailyRequestCap + "). Ajuste ECONOMIZAAI_AI_DAILY_REQUEST_CAP se necessário.");
        }
        try {
            var result = chatAdapter.complete(model, systemPrompt, userPrompt, maxTokens);
            recordUsage(activity, model, result.inputTokens(), result.outputTokens(), true);
            log.info("ai.call activity={} model={} in={} out={}", activity, model,
                    result.inputTokens(), result.outputTokens());
            return result.text();
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException ex) {
            recordUsage(activity, model, 0, 0, false);
            log.warn("ai.call_failed activity={} model={} reason={}", activity, model, ex.getMessage());
            if (isCreditExhausted(ex)) {
                // NOTHING is lost: the deterministic result stands and the pending
                // items stay in their queues (não-casados / sem marca / OTHER) —
                // the next sweep after a recharge covers them all.
                throw new AiUnavailableException(
                        "Créditos da API de IA esgotados — recarregue em console.anthropic.com. "
                        + "Os itens pendentes continuam na fila e serão cobertos na próxima varredura.");
            }
            throw new AiUnavailableException("Falha na chamada de IA: " + ex.getMessage());
        }
    }

    /** Anthropic billing/credit failures ("credit balance is too low", billing_error). */
    static boolean isCreditExhausted(RuntimeException ex) {
        var message = String.valueOf(ex.getMessage()).toLowerCase();
        return message.contains("credit balance") || message.contains("billing")
                || message.contains("purchase credits") || message.contains("insufficient credit");
    }

    private void recordUsage(AiActivity activity, String model, long inputTokens, long outputTokens, boolean success) {
        try {
            usageRepository.save(AiUsageLog.builder()
                    .activity(activity)
                    .model(model)
                    .inputTokens((int) inputTokens)
                    .outputTokens((int) outputTokens)
                    .costUsd(estimateCostUsd(model, inputTokens, outputTokens))
                    .success(success)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("ai.usage_log_failed activity={} reason={}", activity, ex.getMessage());
        }
    }

    static BigDecimal estimateCostUsd(String model, long inputTokens, long outputTokens) {
        var price = PRICES_PER_MTOK.getOrDefault(model, DEFAULT_PRICE);
        var cost = (inputTokens * price[0] + outputTokens * price[1]) / 1_000_000.0;
        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }

    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }
}
