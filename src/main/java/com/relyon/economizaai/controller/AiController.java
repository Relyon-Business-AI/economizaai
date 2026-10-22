package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.AiSweepRun;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.AiModelTier;
import com.relyon.economizaai.model.enums.AiProvider;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiSweepRunRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.ai.AiFindingService;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.ai.AiItemFallbackService;
import com.relyon.economizaai.service.ai.AiProviderSettingsService;
import com.relyon.economizaai.service.ai.AiSubcategorySuggestionService;
import com.relyon.economizaai.service.ai.AiSweepService;
import com.relyon.economizaai.service.ai.AiUsageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin surface of the AI assist layer (all ADMIN-gated in SecurityConfig):
 * sweep (AI scans everything and lists findings for human review), findings
 * review (approve applies via the existing admin services), the spend panel,
 * and an AI test-classify for the admin Testar screen's engine toggle.
 */
@RestController
@RequestMapping("/api/v1/categorizer/ai")
@RequiredArgsConstructor
@Tag(name = "Categorizer AI", description = "AI-assisted curation: sweep, findings review, spend panel")
public class AiController {

    private final AiSweepService aiSweepService;
    private final AiFindingService aiFindingService;
    private final AiSubcategorySuggestionService aiSubcategorySuggestionService;
    private final AiItemFallbackService aiItemFallbackService;
    private final AiUsageService aiUsageService;
    private final AiProviderSettingsService aiProviderSettingsService;
    private final AiGateway aiGateway;
    private final AiSweepRunRepository sweepRunRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final ProductRepository productRepository;
    private final CategoryResolver categoryResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Whether the AI layer is configured (drives FE affordances) + the PENDING
     * QUEUE: everything the deterministic pipeline left for AI. This is the
     * "nothing is lost" guarantee made visible — items skipped by a failed/
     * credit-exhausted sweep stay counted here until a sweep covers them.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var latest = sweepRunRepository.findTopByOrderByCreatedAtDesc().orElse(null);
        var pendingQueue = Map.of(
                "itensNaoCasados", receiptItemRepository.countUnmatched(),
                "produtosSemMarca", productRepository.countByBrandIsNull(),
                "produtosSemNome", productRepository.countByGenericNameIsNull(),
                "produtosOutros", productRepository.countNeedingCategoryReview(categoryResolver.idForSlug("outros")));
        return ResponseEntity.ok(Map.of(
                "enabled", aiGateway.isEnabled(),
                "extractorModel", aiGateway.extractorModel(),
                "curatorModel", aiGateway.curatorModel(),
                "pendingQueue", pendingQueue,
                "lastSweep", latest == null ? Map.of() : Map.of(
                        "id", latest.getId(),
                        "status", latest.getStatus(),
                        "findings", latest.getFindings(),
                        "startedAt", String.valueOf(latest.getCreatedAt()),
                        "error", String.valueOf(latest.getError()))));
    }

    /**
     * Starts the async sweep.
     * ?full=true percorre TODA a fila (modo backfill da base histórica);
     * sem o parâmetro (ou false) roda apenas o lote normal de 40 por módulo.
     */
    @PostMapping("/sweep")
    public ResponseEntity<Map<String, Object>> sweep(@RequestParam(defaultValue = "false") boolean full) {
        var runId = aiSweepService.startSweep(full);
        return ResponseEntity.accepted().body(Map.of("runId", runId, "status", AiSweepRun.STATUS_RUNNING, "full", full));
    }

    @GetMapping("/findings")
    public ResponseEntity<Page<AiFinding>> findings(
            @RequestParam(defaultValue = "PENDING") AiFindingStatus status,
            @RequestParam(required = false) AiFindingType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(aiFindingService.list(status, type, page, size, sort));
    }

    /**
     * AI subcategory-suggestion sweep: scans products that still lack a fine subcategory and
     * files one PENDING suggestion each (with confidence + last price/market) for the admin
     * quick-accept review. Runs in the BACKGROUND (several sequential LLM calls would blow the
     * request timeout) — returns 202 immediately; the FE reloads the list as findings appear.
     */
    @PostMapping("/suggest-subcategories")
    public ResponseEntity<Map<String, Object>> suggestSubcategories(
            @RequestParam(defaultValue = "120") int limit) {
        aiSubcategorySuggestionService.start(limit);
        return ResponseEntity.accepted().body(Map.of("status", "running", "limit", limit));
    }

    /**
     * Aceita o corpo como {@code Map} (que o Jackson 3 do Spring Boot 4 desserializa
     * nativamente) e converte para {@code JsonNode} com o ObjectMapper local — receber
     * o {@code com.fasterxml...JsonNode} direto no corpo quebra, pois o conversor HTTP
     * do Boot 4 é Jackson 3 e não constrói o tipo abstrato do Jackson 2.
     */
    @PostMapping("/findings/{id}/approve")
    public ResponseEntity<AiFinding> approve(@PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> overrides) {
        var overridesNode = (overrides == null || overrides.isEmpty())
                ? null
                : (JsonNode) objectMapper.valueToTree(overrides);
        return ResponseEntity.ok(aiFindingService.approve(id, overridesNode));
    }

    @PostMapping("/findings/{id}/reject")
    public ResponseEntity<AiFinding> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(aiFindingService.reject(id));
    }

    /** UFs where this finding's product/description was scanned — helps spot regional brands. */
    @GetMapping("/findings/{id}/ufs")
    public ResponseEntity<List<String>> findingUfs(@PathVariable UUID id) {
        return ResponseEntity.ok(aiFindingService.resolveUfs(id));
    }

    @PostMapping("/findings/approve-bulk")
    public ResponseEntity<AiFindingService.BulkOutcome> approveBulk(@RequestBody List<UUID> ids) {
        return ResponseEntity.ok(aiFindingService.approveBulk(ids));
    }

    /** "Aceitar todos ≥ X%": approves every pending subcategory suggestion above the confidence threshold. */
    @PostMapping("/findings/approve-subcategories-above")
    public ResponseEntity<AiFindingService.BulkOutcome> approveSubcategoriesAbove(
            @RequestParam(defaultValue = "0.9") BigDecimal threshold) {
        return ResponseEntity.ok(aiFindingService.approveSubcategoriesAbove(threshold));
    }

    /**
     * Diagnostic: re-run the real-time fallback over receipts that still have unmatched items,
     * returning a tally of outcomes (matched / parse_failed / blank_name / already_matched / …)
     * so we can see WHY the fallback persists or discards. ADMIN-only.
     */
    @PostMapping("/reprocess-fallback")
    public ResponseEntity<AiItemFallbackService.FallbackTally> reprocessFallback(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(aiItemFallbackService.reprocessUnmatched(limit));
    }

    @PostMapping("/findings/re-enrich")
    public ResponseEntity<Map<String, Object>> reEnrich() {
        var count = aiFindingService.reEnrich();
        return ResponseEntity.ok(Map.of("enriched", count));
    }

    /** Spend panel: totals + per-activity + per-day, over the last N days. */
    @GetMapping("/usage")
    public ResponseEntity<AiUsageService.UsageSummary> usage(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(aiUsageService.summary(days));
    }

    /**
     * Provedor de IA por tier: config atual + catálogo de opções e quais
     * provedores têm credencial. Dirige o toggle "GPT aqui, Claude ali".
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> aiProviders() {
        return ResponseEntity.ok(Map.of(
                "tiers", List.of(
                        tierView(AiModelTier.EXTRACTOR), tierView(AiModelTier.CURATOR)),
                "providers", List.of(
                        Map.of("provider", AiProvider.ANTHROPIC, "configured", isConfigured(AiProvider.ANTHROPIC),
                                "models", List.of("claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-8")),
                        Map.of("provider", AiProvider.OPENAI, "configured", isConfigured(AiProvider.OPENAI),
                                "models", List.of("gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-4.1", "gpt-4.1-mini", "gpt-4o-mini", "o4-mini")))));
    }

    private Map<String, Object> tierView(AiModelTier tier) {
        var config = aiProviderSettingsService.config(tier);
        return Map.of("tier", tier, "provider", config.provider(), "model", config.model(),
                "configured", isConfigured(config.provider()));
    }

    private boolean isConfigured(AiProvider provider) {
        return aiGateway.providerConfigured(provider);
    }

    /** Troca o provedor/modelo de um tier — efeito imediato, sem redeploy. */
    @PutMapping("/providers/{tier}")
    public ResponseEntity<Map<String, Object>> updateAiProvider(
            @PathVariable AiModelTier tier,
            @Valid @RequestBody AiProviderUpdateRequest request,
            @AuthenticationPrincipal User user) {
        aiProviderSettingsService.update(tier, request.provider(), request.model(),
                user == null ? "admin" : user.getEmail());
        return ResponseEntity.ok((Map<String, Object>) tierView(tier));
    }

    public record AiProviderUpdateRequest(@NotNull AiProvider provider, @NotBlank String model) {}

    /**
     * AI engine for the admin Testar screen's toggle: classifies ONE description
     * with the LLM (same prompt family as the sweep) and returns the proposal.
     * Costs one small call — logged under TEST_CLASSIFY in the spend panel.
     */
    @GetMapping("/classify")
    public ResponseEntity<Map<String, Object>> classify(@RequestParam String description) {
        var categories = Arrays.stream(ProductCategory.values()).map(Enum::name).collect(Collectors.joining(", "));
        var user = """
                Item de cupom fiscal brasileiro: "%s"
                Responda SOMENTE um objeto JSON:
                {"genericName": "<nome limpo sem marca e sem tamanho>", "brand": "<marca ou null>", "category": "<uma de: %s>", "packSize": "<tamanho/volume da embalagem ex: 500ml, 1kg, 37g, ou null>", "unit": "<como é vendido na gôndola: UN para itens embalados/avulsos (padrão), KG para granel pesado, L para granel líquido — NÃO use ML/G aqui>", "confidence": <0..1>, "reason": "<1 frase>"}
                """.formatted(description, categories);
        var system = "Você interpreta descrições abreviadas de cupom fiscal (NFC-e) de supermercado/farmácia no Brasil. "
                + "Responda somente JSON válido, sem markdown. Não invente marca.";
        var text = aiGateway.complete(AiActivity.TEST_CLASSIFY, aiGateway.extractorModel(), system, user, 400);
        return ResponseEntity.ok(parseClassifyResponse(text));
    }

    private Map<String, Object> parseClassifyResponse(String text) {
        try {
            var cleaned = text.trim().replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            var start = cleaned.indexOf('{');
            var end = cleaned.lastIndexOf('}');
            var node = objectMapper.readTree(cleaned.substring(start, end + 1));
            var result = new LinkedHashMap<String, Object>();
            result.put("genericName", node.path("genericName").asText(null));
            result.put("brand", node.path("brand").isNull() ? null : node.path("brand").asText(null));
            result.put("category", node.path("category").asText(null));
            result.put("packSize", node.path("packSize").isNull() ? null : node.path("packSize").asText(null));
            result.put("unit", node.path("unit").isNull() ? null : node.path("unit").asText(null));
            result.put("confidence", node.path("confidence").asDouble(0));
            result.put("reason", node.path("reason").asText(null));
            return result;
        } catch (Exception ex) {
            return Map.of("raw", text, "parseError", String.valueOf(ex.getMessage()));
        }
    }

    @ExceptionHandler(AiGateway.AiUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleUnavailable(AiGateway.AiUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadFinding(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(ex.getMessage())));
    }
}
