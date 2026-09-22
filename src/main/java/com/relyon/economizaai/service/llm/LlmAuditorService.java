package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.relyon.economizaai.model.LlmDisagreement;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.repository.LlmDisagreementRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Nightly quality audit of machine-labeled products. Samples the highest
 * purchase-volume products whose category came from a machine source (human
 * truth is exempt), asks the LLM to verify the stored label, and records
 * disputes as {@link LlmDisagreement} rows for the admin — it NEVER
 * auto-corrects, so labels only change through evidence or humans.
 */
@Slf4j
@Service
public class LlmAuditorService {

    private static final int AUDIT_COOLDOWN_DAYS = 30;

    private final ProductRepository productRepository;
    private final LlmDisagreementRepository disagreementRepository;
    private final OpenAiClient openAiClient;
    private final PaidApiGuardService paidApiGuard;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int sampleSize;

    public LlmAuditorService(ProductRepository productRepository,
                             LlmDisagreementRepository disagreementRepository,
                             OpenAiClient openAiClient,
                             PaidApiGuardService paidApiGuard,
                             TransactionTemplate transactionTemplate,
                             @Value("${economizaai.llm.auditor.enabled:true}") boolean enabled,
                             @Value("${economizaai.llm.auditor.sample-size:50}") int sampleSize) {
        this.productRepository = productRepository;
        this.disagreementRepository = disagreementRepository;
        this.openAiClient = openAiClient;
        this.paidApiGuard = paidApiGuard;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.sampleSize = Math.max(1, sampleSize);
    }

    @Scheduled(cron = "${economizaai.llm.auditor.cron:0 0 4 * * *}")
    public void auditSample() {
        if (!enabled || !openAiClient.isConfigured()) return;
        var cutoff = LocalDateTime.now().minusDays(AUDIT_COOLDOWN_DAYS);
        var sample = productRepository.findAuditSample(cutoff, PageRequest.of(0, sampleSize));
        if (sample.isEmpty()) return;
        try {
            paidApiGuard.assertUnderGlobalBudget();
            paidApiGuard.assertWithinServiceDailyCap(PaidApiService.LLM_ENRICH);
        } catch (RuntimeException ex) {
            log.warn("llm_audit.skipped reason=cost_guard {}", ex.getClass().getSimpleName());
            return;
        }
        JsonNode response;
        try {
            response = openAiClient.completeJson(systemPrompt(), userPrompt(sample), 4000);
            paidApiGuard.recordSuccess(null, PaidApiService.LLM_ENRICH, null, "openai");
        } catch (LlmCallFailedException ex) {
            paidApiGuard.recordFailure(null, PaidApiService.LLM_ENRICH, null, "openai");
            log.warn("llm_audit.call_failed reason={}", ex.getMessage());
            return;
        }
        var disputed = recordVerdicts(sample, response);
        log.info("llm_audit.done sampled={} disputed={}", sample.size(), disputed);
    }

    private String systemPrompt() {
        return """
                Voce audita a categorizacao de produtos de supermercado brasileiros. Para cada item \
                recebera a descricao original e o rotulo armazenado (categoria e marca). Diga se o \
                rotulo esta correto. Seja conservador: conteste apenas erros claros, nao preferencias.
                Responda APENAS o JSON: {"products": [{"id": <id>, "category_ok": true|false, \
                "suggested_category": "...|null", "brand_ok": true|false, \
                "suggested_brand": "...|null", "confidence": <0-1>}]}""";
    }

    private String userPrompt(List<Product> products) {
        var lines = new StringBuilder("Itens:\n");
        for (var index = 0; index < products.size(); index++) {
            var product = products.get(index);
            lines.append(index)
                    .append(": '").append(product.getNormalizedName())
                    .append("' | categoria=").append(product.getCategory())
                    .append(" | marca=").append(product.getBrand() == null ? "(sem)" : product.getBrand())
                    .append('\n');
        }
        return lines.toString();
    }

    private int recordVerdicts(List<Product> sample, JsonNode response) {
        var disputed = 0;
        for (var verdict : response.path("products")) {
            var index = verdict.path("id").asInt(-1);
            if (index < 0 || index >= sample.size()) continue;
            var productId = sample.get(index).getId();
            var wasDisputed = transactionTemplate.execute(txStatus -> {
                var product = productRepository.findById(productId).orElse(null);
                if (product == null) return false;
                product.setLlmAuditedAt(LocalDateTime.now());
                var flagged = false;
                if (!verdict.path("category_ok").asBoolean(true)) {
                    flagged = recordDispute(product, "category", product.getCategory().name(),
                            verdict.path("suggested_category").asText(null), verdict);
                }
                if (!verdict.path("brand_ok").asBoolean(true)) {
                    flagged |= recordDispute(product, "brand", product.getBrand(),
                            verdict.path("suggested_brand").asText(null), verdict);
                }
                productRepository.save(product);
                return flagged;
            });
            if (Boolean.TRUE.equals(wasDisputed)) disputed++;
        }
        return disputed;
    }

    private boolean recordDispute(Product product, String field, String currentValue,
                                  String suggestedValue, JsonNode verdict) {
        if (disagreementRepository.existsByProductIdAndFieldAndResolvedAtIsNull(product.getId(), field)) {
            return false;
        }
        disagreementRepository.save(LlmDisagreement.builder()
                .product(product)
                .field(field)
                .currentValue(currentValue)
                .currentSource(product.getCategorizationSource().name())
                .suggestedValue(suggestedValue)
                .confidence(BigDecimal.valueOf(verdict.path("confidence").asDouble(0)))
                .build());
        log.info("llm_audit.disputed product={} field={} current={} suggested={}",
                product.getId(), field, currentValue, suggestedValue);
        return true;
    }
}
