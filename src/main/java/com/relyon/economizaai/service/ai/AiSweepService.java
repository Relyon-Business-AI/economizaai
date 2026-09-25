package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.AiSweepRun;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.AiSweepRunRepository;
import com.relyon.economizaai.repository.ConsensusGraduationAuditRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.admin.AdminProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

/**
 * The "AI sweeps everything and lists points for the admin to verify" feature.
 * Runs asynchronously (each module = one bounded AI call, no DB transaction held
 * across HTTP), writes {@link AiFinding} rows for human review. NOTHING is
 * applied automatically — approval routes through the existing admin services.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSweepService {

    private static final int MAX_FINDING_TITLE = 290;
    private static final int BATCH_SIZE = 40;
    private static final int ANOMALY_BATCH_SIZE = 150;
    // Teto de segurança do modo completo: 50 páginas × 40 = 2000 itens por módulo.
    private static final int MAX_FULL_PAGES = 50;

    private final AiGateway aiGateway;
    private final AiFindingRepository findingRepository;
    private final AiSweepRunRepository sweepRunRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final ProductRepository productRepository;
    private final ReceiptRepository receiptRepository;
    private final ConsensusGraduationAuditRepository consensusAuditRepository;
    private final AdminProductService adminProductService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Self-reference so the async call goes through the Spring proxy (same
    // pattern as ConsensusPromotionService). Defaults to `this` for unit tests.
    @Lazy
    @Autowired
    private AiSweepService self = this;

    /**
     * Kicks off a normal sweep (lote de 40 por módulo); retorna o run id imediatamente.
     * Uma varredura por vez.
     */
    public UUID startSweep() {
        return startSweep(false);
    }

    /**
     * Varredura completa (full=true): percorre TODA a fila paginando até esvaziá-la
     * ou atingir MAX_FULL_PAGES por módulo. Use para cobrir a base histórica uma vez.
     */
    public UUID startSweep(boolean full) {
        if (!aiGateway.isEnabled()) {
            throw new AiGateway.AiUnavailableException("IA desabilitada: configure ANTHROPIC_API_KEY.");
        }
        if (sweepRunRepository.existsByStatus(AiSweepRun.STATUS_RUNNING)) {
            throw new AiGateway.AiUnavailableException("Já existe uma varredura em execução.");
        }
        var run = sweepRunRepository.save(AiSweepRun.builder()
                .status(AiSweepRun.STATUS_RUNNING).findings(0).build());
        self.runSweep(run.getId(), full);
        return run.getId();
    }

    @Async(AI_SWEEP_EXECUTOR)
    public void runSweep(UUID runId, boolean full) {
        var maxPages = full ? MAX_FULL_PAGES : 1;
        var total = 0;
        try {
            total += runModule("regras",     () -> sweepMissingRules(runId, maxPages));
            total += runModule("marcas",     () -> sweepMissingBrands(runId, maxPages));
            total += runModule("categorias", () -> sweepSuspectCategories(runId, maxPages));
            total += runModule("duplicatas", () -> sweepDuplicates(runId));
            total += runModule("consenso",   () -> sweepConsensus(runId));
            total += runModule("mercados",   () -> sweepMerchants(runId));
            total += runModule("nomes",      () -> sweepFriendlyNames(runId, maxPages));
            total += runModule("anomalias",  () -> sweepAnomalies(runId));
            finishRun(runId, AiSweepRun.STATUS_DONE, total, null);
            log.info("ai.sweep.done run={} full={} findings={}", runId, full, total);
        } catch (RuntimeException ex) {
            finishRun(runId, AiSweepRun.STATUS_FAILED, total, ex.getMessage());
            log.error("ai.sweep.failed run={} findings={} reason={}", runId, total, ex.getMessage());
        }
    }

    // Compat: chamada sem full (testes legados).
    @Async(AI_SWEEP_EXECUTOR)
    public void runSweep(UUID runId) {
        runSweep(runId, false);
    }

    /**
     * A module's PARSE/data failure must not kill the whole sweep — but an
     * unavailability (créditos esgotados / cap diário) must ABORT the remaining
     * modules: calling 7 more times without credit only burns time. Findings
     * already saved stay saved; the pending items remain in their queues and the
     * next sweep after a recharge covers them.
     */
    private int runModule(String name, Supplier<Integer> module) {
        try {
            var created = module.get();
            log.info("ai.sweep.module module={} findings={}", name, created);
            return created;
        } catch (AiGateway.AiUnavailableException unavailable) {
            log.warn("ai.sweep.aborted module={} reason={}", name, unavailable.getMessage());
            throw unavailable;
        } catch (RuntimeException ex) {
            log.warn("ai.sweep.module_failed module={} reason={}", name, ex.getMessage());
            return 0;
        }
    }

    // ── Módulos ─────────────────────────────────────────────────────────────

    private int sweepMissingRules(UUID runId, int maxPages) {
        var created = 0;
        for (var page = 0; page < maxPages; page++) {
            var orphans = receiptItemRepository.topUnmatchedDescriptions(PageRequest.of(page, BATCH_SIZE));
            if (orphans.isEmpty()) break;
            var lines = orphans.stream()
                    .map(row -> "- \"" + row[0] + "\" (vista " + row[1] + "x)")
                    .collect(Collectors.joining("\n"));
            var user = """
                    Descrições de itens de cupom fiscal brasileiro que NÃO casaram com nenhum produto:
                    %s

                    Para cada descrição que você conseguir interpretar, proponha uma regra de dicionário.
                    Responda SOMENTE um array JSON, cada elemento:
                    {"description": "<descrição original>", "keyword": "<1-3 palavras da descrição que identificam o produto, minúsculas>", "genericName": "<nome limpo do produto SEM marca>", "brand": "<marca se identificável, senão null>", "category": "<uma de: %s>", "confidence": <0..1>, "reason": "<1 frase>"}
                    Pule descrições ininteligíveis. Não invente marca.
                    """.formatted(lines, categoryList());
            var text = aiGateway.complete(AiActivity.RULE_SUGGESTION, aiGateway.extractorModel(),
                    systemPrompt(), user, 4000);
            for (var node : parseArray(text)) {
                var keyword = node.path("keyword").asText("");
                var category = node.path("category").asText("");
                if (keyword.isBlank() || parseCategory(category) == null) continue;
                created += saveFinding(runId, AiFindingType.MISSING_RULE, AiActivity.RULE_SUGGESTION,
                        "Regra: \"" + keyword + "\" → " + node.path("genericName").asText("?") + " / " + category,
                        node.path("reason").asText(null) + " (descrição: " + node.path("description").asText("") + ")",
                        node, node.path("confidence").asDouble(0));
            }
            if (orphans.size() < BATCH_SIZE) break;
        }
        return created;
    }

    private int sweepMissingBrands(UUID runId, int maxPages) {
        var created = 0;
        for (var page = 0; page < maxPages; page++) {
            var products = productRepository.findByBrandIsNullOrderByCreatedAtDesc(PageRequest.of(page, BATCH_SIZE));
            if (products.isEmpty()) break;
            var productIdToName = products.stream().collect(
                    Collectors.toMap(product -> product.getId().toString(), product -> product.getNormalizedName()));
            var productById = products.stream().collect(
                    Collectors.toMap(product -> product.getId().toString(), product -> product, (a, b) -> a));
            var lines = products.stream()
                    .map(product -> "- id=" + product.getId() + " \"" + product.getNormalizedName() + "\"")
                    .collect(Collectors.joining("\n"));
            var user = """
                    Produtos de supermercado brasileiros SEM marca identificada (nome vindo do cupom):
                    %s

                    Identifique a marca quando ela estiver visível/abreviada no nome. Responda SOMENTE um array JSON:
                    {"productId": "<id>", "brandKey": "<texto da marca como aparece no cupom, minúsculo>", "brandDisplay": "<nome oficial da marca>", "confidence": <0..1>, "reason": "<1 frase>"}
                    Inclua APENAS produtos onde a marca é clara. Nunca trate palavra genérica de produto (sal, leite, verde) como marca.
                    """.formatted(lines);
            var text = aiGateway.complete(AiActivity.BRAND_SUGGESTION, aiGateway.extractorModel(),
                    systemPrompt(), user, 3000);
            for (var node : parseArray(text)) {
                var productId = node.path("productId").asText("");
                var display = node.path("brandDisplay").asText("");
                if (display.isBlank() || productId.isBlank()) continue;
                var normalizedName = productIdToName.get(productId);
                var matchedProduct = productById.get(productId);
                var enriched = objectMapper.createObjectNode();
                node.fields().forEachRemaining(entry -> enriched.set(entry.getKey(), entry.getValue()));
                enriched.put("normalizedName", normalizedName != null ? normalizedName : productId);
                enriched.put("productCategory", matchedProduct != null && matchedProduct.getCategory() != null ? matchedProduct.getCategory().name() : "");
                created += saveFinding(runId, AiFindingType.MISSING_BRAND, AiActivity.BRAND_SUGGESTION,
                        "Marca: \"" + node.path("brandKey").asText("") + "\" → " + display,
                        node.path("reason").asText(null), enriched, node.path("confidence").asDouble(0));
            }
            if (products.size() < BATCH_SIZE) break;
        }
        return created;
    }

    private int sweepSuspectCategories(UUID runId, int maxPages) {
        var created = 0;
        for (var page = 0; page < maxPages; page++) {
            var products = productRepository.findByCategoryOrderByCreatedAtDesc(ProductCategory.OTHER, PageRequest.of(page, BATCH_SIZE));
            if (products.isEmpty()) break;
            var productIdToName = products.stream().collect(
                    Collectors.toMap(product -> product.getId().toString(), product -> product.getNormalizedName()));
            var productById = products.stream().collect(
                    Collectors.toMap(product -> product.getId().toString(), product -> product, (a, b) -> a));
            var lines = products.stream()
                    .map(product -> "- id=" + product.getId() + " \"" + product.getNormalizedName() + "\"")
                    .collect(Collectors.joining("\n"));
            var user = """
                    Produtos atualmente na categoria OTHER (não classificados):
                    %s

                    Proponha a categoria correta. Responda SOMENTE um array JSON:
                    {"productId": "<id>", "category": "<uma de: %s>", "confidence": <0..1>, "reason": "<1 frase>"}
                    Pule os que realmente não dá para classificar.
                    """.formatted(lines, categoryList());
            var text = aiGateway.complete(AiActivity.CATEGORY_REVIEW, aiGateway.extractorModel(),
                    systemPrompt(), user, 3000);
            for (var node : parseArray(text)) {
                var category = parseCategory(node.path("category").asText(""));
                if (category == null || category == ProductCategory.OTHER) continue;
                var productId = node.path("productId").asText("");
                var normalizedName = productIdToName.getOrDefault(productId, productId);
                var matchedProduct = productById.get(productId);
                var enriched = objectMapper.createObjectNode();
                node.fields().forEachRemaining(entry -> enriched.set(entry.getKey(), entry.getValue()));
                enriched.put("normalizedName", normalizedName);
                enriched.put("currentCategory", "OTHER");
                enriched.put("productBrand", matchedProduct != null && matchedProduct.getBrand() != null ? matchedProduct.getBrand() : "");
                created += saveFinding(runId, AiFindingType.SUSPECT_CATEGORY, AiActivity.CATEGORY_REVIEW,
                        "Categoria: \"" + normalizedName + "\" → " + category,
                        node.path("reason").asText(null), enriched, node.path("confidence").asDouble(0));
            }
            if (products.size() < BATCH_SIZE) break;
        }
        return created;
    }

    private int sweepDuplicates(UUID runId) {
        var groups = adminProductService.listDuplicateGroups();
        if (groups.isEmpty()) return 0;
        var idToName = groups.stream()
                .flatMap(group -> group.products().stream())
                .collect(Collectors.toMap(product -> product.id().toString(), product -> product.normalizedName(), (a, b) -> a));
        var lines = new StringBuilder();
        for (var group : groups) {
            lines.append("Grupo ").append(group.genericName()).append(" / ").append(group.brand()).append(":\n");
            group.products().forEach(product ->
                    lines.append("  - id=").append(product.id()).append(" \"").append(product.normalizedName()).append("\"\n"));
        }
        var user = """
                Grupos de produtos com METADADOS iguais que PODEM ser duplicatas (ou produtos distintos, ex.: sal grosso ≠ sal refinado, patê de atum ≠ atum):
                %s

                Para cada grupo decida se são o MESMO produto físico. Responda SOMENTE um array JSON (um por grupo onde há fusão a fazer):
                {"survivorId": "<id do que fica>", "absorbedId": "<id do que some>", "sameProduct": true, "confidence": <0..1>, "reason": "<1 frase>"}
                Se são produtos diferentes, NÃO inclua o grupo.
                """.formatted(lines);
        var text = aiGateway.complete(AiActivity.DUPLICATE_JUDGE, aiGateway.curatorModel(),
                systemPrompt(), user, 2000);
        var created = 0;
        for (var node : parseArray(text)) {
            if (!node.path("sameProduct").asBoolean(false)) continue;
            var survivorId = node.path("survivorId").asText("");
            var absorbedId = node.path("absorbedId").asText("");
            var survivorName = idToName.getOrDefault(survivorId, shortId(survivorId));
            var absorbedName = idToName.getOrDefault(absorbedId, shortId(absorbedId));
            var enriched = enrichedPayload(node, Map.of("survivorName", survivorName, "absorbedName", absorbedName));
            created += saveFinding(runId, AiFindingType.DUPLICATE, AiActivity.DUPLICATE_JUDGE,
                    "Fundir: \"" + absorbedName + "\" → \"" + survivorName + "\"",
                    node.path("reason").asText(null), enriched, node.path("confidence").asDouble(0));
        }
        return created;
    }

    private int sweepConsensus(UUID runId) {
        var audits = consensusAuditRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20));
        if (audits.isEmpty()) return 0;
        var lines = audits.stream().map(audit -> {
            var product = productRepository.findById(audit.getProductId()).orElse(null);
            var name = product != null ? product.getNormalizedName() : "?";
            return "- produto \"" + name + "\" graduado para " + audit.getCategory() + " por " + audit.getVotes() + " households";
        }).collect(Collectors.joining("\n"));
        var user = """
                Graduações de categoria por CONSENSO de usuários (correções que viraram verdade global):
                %s

                Sinalize APENAS as que parecem ERRADAS (categoria não condiz com o produto). Responda SOMENTE um array JSON:
                {"productName": "<nome>", "gradedCategory": "<categoria atual>", "suggestedCategory": "<uma de: %s>", "confidence": <0..1>, "reason": "<1 frase>"}
                Se todas parecem corretas, responda [].
                """.formatted(lines, categoryList());
        var text = aiGateway.complete(AiActivity.CONSENSUS_JUDGE, aiGateway.curatorModel(),
                systemPrompt(), user, 1500);
        var created = 0;
        for (var node : parseArray(text)) {
            created += saveFinding(runId, AiFindingType.CONSENSUS_REVIEW, AiActivity.CONSENSUS_JUDGE,
                    "Consenso suspeito: \"" + node.path("productName").asText("?") + "\" como " + node.path("gradedCategory").asText("?"),
                    node.path("reason").asText(null), node, node.path("confidence").asDouble(0));
        }
        return created;
    }

    private int sweepMerchants(UUID runId) {
        var merchants = receiptRepository.findDistinctMerchants(PageRequest.of(0, 30));
        if (merchants.isEmpty()) return 0;
        var lines = merchants.stream()
                .map(row -> "- \"" + row[1] + "\" (cnpj " + row[0] + ")")
                .collect(Collectors.joining("\n"));
        var user = """
                Estabelecimentos vistos em cupons fiscais:
                %s

                Classifique o segmento de cada um. Responda SOMENTE um array JSON:
                {"marketName": "<nome>", "segment": "<SUPERMARKET|PHARMACY|OTHER>", "confidence": <0..1>}
                Sinalize com segment OTHER os que NÃO são supermercado nem farmácia (ex.: restaurante, posto, loja de roupas).
                """.formatted(lines);
        var text = aiGateway.complete(AiActivity.MERCHANT_CLASSIFY, aiGateway.extractorModel(),
                systemPrompt(), user, 2000);
        var created = 0;
        for (var node : parseArray(text)) {
            if (!"OTHER".equalsIgnoreCase(node.path("segment").asText("")) &&
                !"PHARMACY".equalsIgnoreCase(node.path("segment").asText(""))) continue;
            created += saveFinding(runId, AiFindingType.MERCHANT_REVIEW, AiActivity.MERCHANT_CLASSIFY,
                    "Mercado: \"" + node.path("marketName").asText("?") + "\" parece " + node.path("segment").asText("?"),
                    null, node, node.path("confidence").asDouble(0));
        }
        return created;
    }

    private int sweepFriendlyNames(UUID runId, int maxPages) {
        var created = 0;
        for (var page = 0; page < maxPages; page++) {
            var products = productRepository.findByGenericNameIsNullOrderByCreatedAtDesc(PageRequest.of(page, BATCH_SIZE));
            if (products.isEmpty()) break;
            var productIdToName = products.stream().collect(
                    Collectors.toMap(product -> product.getId().toString(), product -> product.getNormalizedName()));
            var productById = products.stream().collect(
                    Collectors.toMap(product -> product.getId().toString(), product -> product, (a, b) -> a));
            var lines = products.stream()
                    .map(product -> "- id=" + product.getId() + " \"" + product.getNormalizedName() + "\"")
                    .collect(Collectors.joining("\n"));
            var user = """
                    Produtos SEM nome genérico amigável (só o texto cru do cupom):
                    %s

                    Proponha um nome limpo e curto em português (ex.: "Arroz Branco", "Detergente"), SEM marca e SEM tamanho.
                    Responda SOMENTE um array JSON:
                    {"productId": "<id>", "genericName": "<nome amigável>", "confidence": <0..1>}
                    Pule os ininteligíveis.
                    """.formatted(lines);
            var text = aiGateway.complete(AiActivity.FRIENDLY_NAMES, aiGateway.extractorModel(),
                    systemPrompt(), user, 3000);
            for (var node : parseArray(text)) {
                var genericName = node.path("genericName").asText("");
                if (genericName.isBlank()) continue;
                var productId = node.path("productId").asText("");
                var normalizedName = productIdToName.getOrDefault(productId, productId);
                var matchedProduct = productById.get(productId);
                var enriched = objectMapper.createObjectNode();
                node.fields().forEachRemaining(entry -> enriched.set(entry.getKey(), entry.getValue()));
                enriched.put("normalizedName", normalizedName);
                enriched.put("productBrand", matchedProduct != null && matchedProduct.getBrand() != null ? matchedProduct.getBrand() : "");
                enriched.put("productCategory", matchedProduct != null && matchedProduct.getCategory() != null ? matchedProduct.getCategory().name() : "");
                created += saveFinding(runId, AiFindingType.FRIENDLY_NAME, AiActivity.FRIENDLY_NAMES,
                        "Nome: \"" + normalizedName + "\" → \"" + genericName + "\"",
                        null, enriched, node.path("confidence").asDouble(0));
            }
            if (products.size() < BATCH_SIZE) break;
        }
        return created;
    }

    private int sweepAnomalies(UUID runId) {
        var items = receiptItemRepository.findRecentConfirmedWithReceipt(PageRequest.of(0, 150));
        if (items.isEmpty()) return 0;
        var lines = items.stream()
                .map(item -> "- [" + item.getReceipt().getMarketName() + "] \"" + item.getRawDescription()
                        + "\" qtd=" + item.getQuantity() + " total=R$" + item.getTotalPrice())
                .collect(Collectors.joining("\n"));
        var user = """
                Itens recentes de cupons fiscais (mercado, descrição, quantidade, total pago):
                %s

                Sinalize APENAS anomalias claras: preço absurdo para o tipo de produto (ex.: arroz a R$900),
                quantidade impossível, ou o mesmo item repetido com valores idênticos suspeitos.
                Responda SOMENTE um array JSON:
                {"description": "<descrição>", "market": "<mercado>", "issue": "<1 frase do problema>", "confidence": <0..1>}
                Se nada é anômalo, responda [].
                """.formatted(lines);
        var text = aiGateway.complete(AiActivity.ANOMALY_SCAN, aiGateway.curatorModel(),
                systemPrompt(), user, 1500);
        var created = 0;
        for (var node : parseArray(text)) {
            created += saveFinding(runId, AiFindingType.ANOMALY, AiActivity.ANOMALY_SCAN,
                    "Anomalia: \"" + node.path("description").asText("?") + "\" — " + node.path("issue").asText("?"),
                    "Mercado: " + node.path("market").asText("?"), node, node.path("confidence").asDouble(0));
        }
        return created;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String systemPrompt() {
        return "Você é o curador de dados do economizaai, um app brasileiro que lê cupons fiscais (NFC-e) de "
                + "supermercado/farmácia. Você entende as abreviações típicas de cupom (ex.: SHAMP=shampoo, FERM=fermento, "
                + "REFRIG=refrigerante, C/=com). Seja conservador: só proponha quando tiver confiança; nunca invente. "
                + "Responda SEMPRE somente com JSON válido, sem markdown, sem texto fora do JSON.";
    }

    private String categoryList() {
        return Arrays.stream(ProductCategory.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    private ProductCategory parseCategory(String raw) {
        try {
            return ProductCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Defensive parse: strips code fences, tolerates a non-array root, returns [] on garbage. */
    List<JsonNode> parseArray(String text) {
        if (text == null) return List.of();
        var cleaned = text.trim()
                .replaceAll("^```(json)?", "")
                .replaceAll("```$", "")
                .trim();
        var start = cleaned.indexOf('[');
        var end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            var root = objectMapper.readTree(cleaned.substring(start, end + 1));
            if (!root.isArray()) return List.of();
            var nodes = new ArrayList<JsonNode>();
            root.forEach(nodes::add);
            return nodes;
        } catch (Exception ex) {
            log.warn("ai.sweep.parse_failed reason={}", ex.getMessage());
            return List.of();
        }
    }

    private int saveFinding(UUID runId, AiFindingType type, AiActivity activity,
                            String title, String detail, JsonNode payload, double confidence) {
        var safeTitle = title.length() > MAX_FINDING_TITLE ? title.substring(0, MAX_FINDING_TITLE) : title;
        // Don't re-propose something already reviewed or still pending with the same title.
        if (findingRepository.existsByTypeAndTitle(type, safeTitle)) return 0;
        findingRepository.save(AiFinding.builder()
                .sweepRunId(runId)
                .type(type)
                .status(AiFindingStatus.PENDING)
                .activity(activity)
                .title(safeTitle)
                .detail(detail)
                .payload(payload.toString())
                .confidence(BigDecimal.valueOf(Math.max(0, Math.min(1, confidence))).setScale(3, RoundingMode.HALF_UP))
                .build());
        return 1;
    }

    private void finishRun(UUID runId, String status, int findings, String error) {
        sweepRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setFindings(findings);
            run.setError(error != null && error.length() > 490 ? error.substring(0, 490) : error);
            sweepRunRepository.save(run);
        });
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private ObjectNode enrichedPayload(JsonNode base, Map<String, String> extra) {
        var node = objectMapper.createObjectNode();
        base.fields().forEachRemaining(entry -> node.set(entry.getKey(), entry.getValue()));
        extra.forEach((key, value) -> {
            if (value != null) node.put(key, value);
            else node.putNull(key);
        });
        return node;
    }
}
