package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.AiSweepRun;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiSweepRunRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.ai.AiFindingService;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.ai.AiSweepService;
import com.relyon.economizaai.service.ai.AiUsageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final AiUsageService aiUsageService;
    private final AiGateway aiGateway;
    private final AiSweepRunRepository sweepRunRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final ProductRepository productRepository;

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
                "produtosOutros", productRepository.countByCategory(ProductCategory.OTHER));
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
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(aiFindingService.list(status, type, page, size));
    }

    @PostMapping("/findings/{id}/approve")
    public ResponseEntity<AiFinding> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(aiFindingService.approve(id));
    }

    @PostMapping("/findings/{id}/reject")
    public ResponseEntity<AiFinding> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(aiFindingService.reject(id));
    }

    @PostMapping("/findings/approve-bulk")
    public ResponseEntity<AiFindingService.BulkOutcome> approveBulk(@RequestBody List<UUID> ids) {
        return ResponseEntity.ok(aiFindingService.approveBulk(ids));
    }

    /** Spend panel: totals + per-activity + per-day, over the last N days. */
    @GetMapping("/usage")
    public ResponseEntity<AiUsageService.UsageSummary> usage(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(aiUsageService.summary(days));
    }

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
                {"genericName": "<nome limpo sem marca>", "brand": "<marca ou null>", "category": "<uma de: %s>", "packSize": "<tamanho ou null>", "confidence": <0..1>, "reason": "<1 frase>"}
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
