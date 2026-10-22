package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiUsageLog;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiModelTier;
import com.relyon.economizaai.model.enums.AiProvider;
import com.relyon.economizaai.repository.AiUsageLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

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
public class AiGateway {

    /** USD per 1M tokens (input, output) — estimativa local pro painel de gastos. */
    private static final Map<String, double[]> PRICES_PER_MTOK = new java.util.HashMap<>();
    static {
        // Anthropic
        PRICES_PER_MTOK.put("claude-haiku-4-5", new double[]{1.0, 5.0});
        PRICES_PER_MTOK.put("claude-sonnet-4-6", new double[]{3.0, 15.0});
        PRICES_PER_MTOK.put("claude-opus-4-8", new double[]{5.0, 25.0});
        // OpenAI (estimativa; só afeta o painel, não a cobrança real)
        PRICES_PER_MTOK.put("gpt-5", new double[]{1.25, 10.0});
        PRICES_PER_MTOK.put("gpt-5-mini", new double[]{0.25, 2.0});
        PRICES_PER_MTOK.put("gpt-5-nano", new double[]{0.05, 0.40});
        PRICES_PER_MTOK.put("gpt-4.1", new double[]{2.0, 8.0});
        PRICES_PER_MTOK.put("gpt-4.1-mini", new double[]{0.40, 1.60});
        PRICES_PER_MTOK.put("gpt-4o", new double[]{2.5, 10.0});
        PRICES_PER_MTOK.put("gpt-4o-mini", new double[]{0.15, 0.60});
        PRICES_PER_MTOK.put("o4-mini", new double[]{1.10, 4.40});
    }
    private static final double[] DEFAULT_PRICE = {3.0, 15.0};

    private final Map<AiProvider, AiChatPort> adapters;
    private final AiProviderSettingsService providerSettings;
    private final AiUsageLogRepository usageRepository;

    @Value("${economizaai.ai.daily-request-cap:300}")
    private int dailyRequestCap;

    @Value("${economizaai.ai.budget-usd:0}")
    private double budgetUsd;

    public AiGateway(java.util.List<AiChatPort> chatAdapters,
                     AiProviderSettingsService providerSettings,
                     AiUsageLogRepository usageRepository) {
        this.adapters = chatAdapters.stream()
                .collect(java.util.stream.Collectors.toMap(AiChatPort::provider, adapter -> adapter));
        this.providerSettings = providerSettings;
        this.usageRepository = usageRepository;
    }

    /** Ligado se QUALQUER provedor tem credencial — o adapter específico é validado por chamada. */
    public boolean isEnabled() {
        return adapters.values().stream().anyMatch(AiChatPort::isConfigured);
    }

    /** Se um provedor específico tem credencial (dirige o toggle do admin). */
    public boolean providerConfigured(AiProvider provider) {
        var adapter = adapters.get(provider);
        return adapter != null && adapter.isConfigured();
    }

    /** Rótulo do provedor que serve este modelo (ANTHROPIC/OPENAI) — pro benchmark. */
    public String providerLabelForModel(String model) {
        return providerForModel(model).name();
    }

    public String extractorModel() {
        return providerSettings.config(AiModelTier.EXTRACTOR).model();
    }

    public String curatorModel() {
        return providerSettings.config(AiModelTier.CURATOR).model();
    }

    /** Modelo que serve esta atividade (pelo tier) — usado pra atribuir o achado ao modelo. */
    public String modelFor(AiActivity activity) {
        return providerSettings.config(activity.tier()).model();
    }

    /** Resolve o adapter que serve este modelo (via config de tier; fallback por prefixo). */
    private AiChatPort adapterForModel(String model) {
        var provider = providerForModel(model);
        var adapter = adapters.get(provider);
        if (adapter == null || !adapter.isConfigured()) {
            throw new AiUnavailableException("Provedor de IA " + provider
                    + " não configurado (falta a API key). Ajuste em Admin → Categorização → Provedor de IA.");
        }
        return adapter;
    }

    private AiProvider providerForModel(String model) {
        if (model != null) {
            if (model.equals(providerSettings.config(AiModelTier.EXTRACTOR).model())) {
                return providerSettings.config(AiModelTier.EXTRACTOR).provider();
            }
            if (model.equals(providerSettings.config(AiModelTier.CURATOR).model())) {
                return providerSettings.config(AiModelTier.CURATOR).provider();
            }
            var lower = model.toLowerCase();
            if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")) {
                return AiProvider.OPENAI;
            }
        }
        return AiProvider.ANTHROPIC;
    }

    public double budgetUsd() {
        return budgetUsd;
    }

    /**
     * Runs one AI call, enforcing the daily cap and logging usage. Throws
     * {@link AiUnavailableException} when disabled or over budget — callers
     * surface that as a clean admin-facing message instead of a stack trace.
     */
    public String complete(AiActivity activity, String model, String systemPrompt, String userPrompt, int maxTokens) {
        return complete(activity, model, systemPrompt, userPrompt, maxTokens, null);
    }

    /** As {@link #complete(AiActivity, String, String, String, int)} but attributes the cost to {@code userId}. */
    public String complete(AiActivity activity, String model, String systemPrompt, String userPrompt, int maxTokens,
                           UUID userId) {
        if (!isEnabled()) {
            throw new AiUnavailableException("IA desabilitada: nenhum provedor com API key configurada.");
        }
        var todayStart = LocalDate.now().atStartOfDay();
        var callsToday = usageRepository.countByCreatedAtAfter(todayStart);
        if (callsToday >= dailyRequestCap) {
            throw new AiUnavailableException(
                    "Cap diário de chamadas de IA atingido (" + dailyRequestCap + "). Ajuste ECONOMIZAAI_AI_DAILY_REQUEST_CAP se necessário.");
        }
        var startNanos = System.nanoTime();
        try {
            var result = adapterForModel(model).complete(model, systemPrompt, userPrompt, maxTokens);
            recordUsage(activity, model, result.inputTokens(), result.outputTokens(), true, userId, elapsedMs(startNanos));
            log.info("ai.call activity={} model={} in={} out={} cache_write={} cache_read={} ms={}", activity, model,
                    result.inputTokens(), result.outputTokens(), result.cacheWriteTokens(), result.cacheReadTokens(),
                    elapsedMs(startNanos));
            return result.text();
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException ex) {
            recordUsage(activity, model, 0, 0, false, userId, elapsedMs(startNanos));
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

    /**
     * Multimodal variant of {@link #complete} — one image + prompt. Same enablement
     * check, daily cap, ledger and credit-exhaustion handling.
     */
    public String completeWithImage(AiActivity activity, String model, String systemPrompt, String userPrompt,
                                    byte[] imageBytes, String imageMediaType, int maxTokens) {
        return completeWithImage(activity, model, systemPrompt, userPrompt, imageBytes, imageMediaType, maxTokens, null);
    }

    /** As the 7-arg {@link #completeWithImage} but attributes the cost to {@code userId}. */
    public String completeWithImage(AiActivity activity, String model, String systemPrompt, String userPrompt,
                                    byte[] imageBytes, String imageMediaType, int maxTokens, UUID userId) {
        if (!isEnabled()) {
            throw new AiUnavailableException("IA desabilitada: nenhum provedor com API key configurada.");
        }
        var todayStart = LocalDate.now().atStartOfDay();
        if (usageRepository.countByCreatedAtAfter(todayStart) >= dailyRequestCap) {
            throw new AiUnavailableException("Cap diário de chamadas de IA atingido (" + dailyRequestCap + ").");
        }
        var startNanos = System.nanoTime();
        try {
            var result = adapterForModel(model).completeWithImage(model, systemPrompt, userPrompt, imageBytes, imageMediaType, maxTokens);
            recordUsage(activity, model, result.inputTokens(), result.outputTokens(), true, userId, elapsedMs(startNanos));
            log.info("ai.call activity={} model={} in={} out={} ms={} (vision)", activity, model,
                    result.inputTokens(), result.outputTokens(), elapsedMs(startNanos));
            return result.text();
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException ex) {
            recordUsage(activity, model, 0, 0, false, userId, elapsedMs(startNanos));
            log.warn("ai.call_failed activity={} model={} reason={}", activity, model, ex.getMessage());
            if (isCreditExhausted(ex)) {
                throw new AiUnavailableException("Créditos da API de IA esgotados — recarregue em console.anthropic.com.");
            }
            throw new AiUnavailableException("Falha na chamada de IA: " + ex.getMessage());
        }
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    /** Anthropic billing/credit failures ("credit balance is too low", billing_error). */
    static boolean isCreditExhausted(RuntimeException ex) {
        var message = String.valueOf(ex.getMessage()).toLowerCase();
        return message.contains("credit balance") || message.contains("billing")
                || message.contains("purchase credits") || message.contains("insufficient credit");
    }

    private void recordUsage(AiActivity activity, String model, long inputTokens, long outputTokens, boolean success,
                             UUID userId, int durationMs) {
        try {
            usageRepository.save(AiUsageLog.builder()
                    .activity(activity)
                    .model(model)
                    .inputTokens((int) inputTokens)
                    .outputTokens((int) outputTokens)
                    .costUsd(estimateCostUsd(model, inputTokens, outputTokens))
                    .success(success)
                    .userId(userId)
                    .durationMs(durationMs)
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
