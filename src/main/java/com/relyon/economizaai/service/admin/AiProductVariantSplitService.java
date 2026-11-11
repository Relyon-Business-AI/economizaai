package com.relyon.economizaai.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.dto.response.AiProductVariantSplitResponse;
import com.relyon.economizaai.dto.response.ProductVariantSplitResponse.ProductSplitPlan;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.canonicalization.EanAuthority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI-assisted variant split for the RESIDUAL long tail the deterministic dictionary
 * ({@code BrandExtractor} + {@code VariantDiscriminator}) cannot disambiguate — e.g.
 * "CAFE MELITTA 500G" still mixing Pimpinela / Canecão / Melitta (the extractor returns
 * "café" for every one), brand×flavour collisions ("orquídea" is both a brand AND a soap
 * scent), some Tang flavours. The deterministic split (see {@link ProductVariantSplitService})
 * already ran; this covers what it leaves mixed.
 *
 * <p><b>Candidate gate (cost bound).</b> We do NOT run the AI over all ~7000 products.
 * Only the variant-conflict set is considered: a product whose linked confirmed items carry
 * more than one distinct real EAN OR more than one deterministic signature (i.e. STILL mixed
 * after the deterministic pass). Capped at {@code max}. The number of candidates processed is
 * logged.
 *
 * <p><b>The AI call.</b> For each candidate, the distinct items (raw_description + ean + count)
 * are sent to {@link AiGateway} (Anthropic / closed-set clustering model) with a structured
 * JSON schema: {@code {"groups": [[itemId, ...], ...]}}. Same brand + same flavour/variant +
 * same size ⇒ same group; different brand/flavour/variant ⇒ separate. The call is untransacted
 * (never holds a DB connection across the outbound HTTP) and the grouping is cached per
 * product + item-set hash so re-runs don't re-pay.
 *
 * <p><b>EAN cohesion is enforced OVER the AI.</b> After the AI returns groups, any items
 * sharing the same real GTIN ({@link EanAuthority#isReal}) are merged into ONE group even if
 * the AI split them — a shared real GTIN is ground truth the AI never overrides. If the AI
 * returns a single group (all the same product) ⇒ no split.
 *
 * <p><b>Apply.</b> The AI-derived groups are applied through the EXISTING split mechanics
 * (new products created, {@code receipt_items} / {@code price_observations} / {@code product_aliases}
 * repointed, {@code generic_name_norm} preserved) via {@link ProductVariantSplitService#applyGroups}.
 * {@code dryRun=true} returns the proposed groupings and mutates nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProductVariantSplitService {

    private static final int DEFAULT_MAX = 50;
    private static final int HARD_CAP = 200;
    private static final int MAX_TOKENS = 1500;

    private static final String SYSTEM_PROMPT = """
            Você agrupa itens de cupom fiscal brasileiro que foram ERRADAMENTE unidos
            sob um único produto. Cada item traz: id, texto bruto (raw), e o código de
            barras (ean, pode ser vazio).

            Agrupe os itens que são O MESMO produto real. Dois itens são o MESMO produto
            quando têm a MESMA marca E o MESMO sabor/variante E o MESMO tamanho/volume.
            Itens com marca diferente, sabor/variante diferente, ou tipo diferente vão em
            grupos SEPARADOS. Ignore abreviações e grafias diferentes do mesmo produto.

            Responda SOMENTE um JSON, sem texto fora dele, no formato:
            {"groups": [["id1","id2"], ["id3"]]}
            onde cada array interno é um produto real distinto e contém os ids dos itens
            que pertencem a ele. Todo id recebido deve aparecer em exatamente um grupo.
            Se todos os itens forem o MESMO produto, retorne um único grupo com todos os ids.
            """;

    private final ProductVariantSplitService deterministicSplit;
    private final ProductRepository productRepository;
    private final AiGateway aiGateway;
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Grouping cache keyed by product id + item-set hash, so a re-run doesn't re-pay the AI. */
    private final Map<String, List<List<String>>> groupingCache = new ConcurrentHashMap<>();

    /**
     * Plans (dryRun) or applies the AI split over the still-mixed variant-conflict set.
     * No-op (reports {@code aiAvailable=false}) when no AI provider has an API key.
     */
    public AiProductVariantSplitResponse splitVariants(int maxProducts, boolean dryRun) {
        if (!aiGateway.isEnabled()) {
            log.info("admin.product.ai_split.skipped reason=ai_unavailable");
            return AiProductVariantSplitResponse.unavailable(dryRun);
        }

        var cap = Math.max(1, Math.min(maxProducts <= 0 ? DEFAULT_MAX : maxProducts, HARD_CAP));
        var candidateIds = stillMixedCandidates(cap);

        var plans = new ArrayList<ProductSplitPlan>();
        var newProductsCreated = 0;
        var processed = 0;
        for (var productId : candidateIds) {
            try {
                var outcome = processOne(productId, dryRun);
                processed++;
                if (outcome == null) continue; // single AI group (or gone) — nothing to split
                plans.add(outcome.plan());
                newProductsCreated += outcome.newProducts();
            } catch (AiGateway.AiUnavailableException ex) {
                // Budget/credit cap hit mid-run — stop spending, return what we have.
                log.warn("admin.product.ai_split.halted processed={} reason={}", processed, ex.getMessage());
                break;
            } catch (RuntimeException ex) {
                log.warn("admin.product.ai_split.failed product={} reason={}", abbrev(productId), ex.getMessage());
            }
        }

        log.info("admin.product.ai_split done dryRun={} candidates={} processed={} split={} newProducts={}",
                dryRun, candidateIds.size(), processed, plans.size(), newProductsCreated);
        return new AiProductVariantSplitResponse(true, dryRun, candidateIds.size(), plans.size(),
                newProductsCreated, plans);
    }

    /**
     * The candidate gate: of the cheap SQL pre-filter set, keep only the products STILL mixed
     * after the deterministic pass — more than one distinct real EAN OR more than one
     * deterministic signature. Runs no AI; each read is a short, plain repository call.
     */
    private List<UUID> stillMixedCandidates(int cap) {
        var preFilter = deterministicSplit.candidateProductIds(cap);
        var mixed = new ArrayList<UUID>();
        for (var productId : preFilter) {
            var items = deterministicSplit.linkedItems(productId);
            if (items.isEmpty()) continue;
            var multipleRealEans = deterministicSplit.distinctRealEanCount(items) > 1;
            var multipleSignatures = deterministicSplit.deterministicSignatureCount(items) > 1;
            if (multipleRealEans || multipleSignatures) mixed.add(productId);
        }
        return mixed;
    }

    /** One candidate: gather items → AI cluster → EAN cohesion → plan → (optionally) apply. */
    private SplitOutcome processOne(UUID productId, boolean dryRun) {
        var product = productRepository.findById(productId).orElse(null);
        if (product == null) return null;
        var items = deterministicSplit.linkedItems(productId);
        if (items.size() < 2) return null;

        var itemsById = indexItems(items);
        var aiGroups = clusterWithAi(productId, itemsById); // untransacted outbound HTTP
        var groupsByLabel = toEanCohesiveGroups(aiGroups, itemsById);
        if (groupsByLabel.size() < 2) {
            log.info("admin.product.ai_split.no_split product={} groups={}", abbrev(productId), groupsByLabel.size());
            return null;
        }

        var plan = deterministicSplit.planFromGroups(product, groupsByLabel);
        if (plan == null) return null;

        if (dryRun) {
            var proposed = (int) plan.resultingProducts().stream().filter(group -> !group.anchor()).count();
            return new SplitOutcome(plan, proposed);
        }
        // Apply in a short transaction AFTER the AI call committed nothing — never hold a DB
        // connection across the outbound HTTP (CLAUDE.md).
        var created = transactionTemplate.execute(txStatus ->
                deterministicSplit.applyGroups(product, plan, groupsByLabel));
        return new SplitOutcome(plan, created == null ? 0 : created);
    }

    /** Stable "item:N" id per receipt item (so the AI references items by a short token). */
    private Map<String, ReceiptItem> indexItems(List<ReceiptItem> items) {
        var byId = new LinkedHashMap<String, ReceiptItem>();
        var index = 0;
        for (var item : items) {
            byId.put("item:" + index++, item);
        }
        return byId;
    }

    /**
     * Calls the AI to cluster the distinct items, returning the groups as lists of item ids.
     * Cached per product + item-set hash. On an unparseable response, falls back to one group
     * per item (no merging) so EAN cohesion still salvages the real GTIN groupings.
     */
    private List<List<String>> clusterWithAi(UUID productId, Map<String, ReceiptItem> itemsById) {
        var distinct = distinctItemsPayload(itemsById);
        var cacheKey = productId + ":" + hash(distinct);
        var cached = groupingCache.get(cacheKey);
        if (cached != null) {
            log.info("admin.product.ai_split.cache_hit product={}", abbrev(productId));
            return cached;
        }

        var userPrompt = "Itens:\n" + distinct;
        var raw = aiGateway.complete(AiActivity.VARIANT_SPLIT, aiGateway.curatorModel(),
                SYSTEM_PROMPT, userPrompt, MAX_TOKENS);
        var groups = parseGroups(raw, itemsById.keySet());
        groupingCache.put(cacheKey, groups);
        return groups;
    }

    /** Distinct (raw, ean) items with a count — the material the AI clusters. One line per item id. */
    private String distinctItemsPayload(Map<String, ReceiptItem> itemsById) {
        var lines = new ArrayList<String>();
        for (var entry : itemsById.entrySet()) {
            var item = entry.getValue();
            var ean = item.getEan() == null ? "" : item.getEan().trim();
            lines.add("{\"id\":\"" + entry.getKey() + "\",\"raw\":\"" + escape(item.getRawDescription())
                    + "\",\"ean\":\"" + escape(ean) + "\"}");
        }
        return String.join("\n", lines);
    }

    /** Parses {"groups":[[id,...],...]}; keeps only known ids; any id the AI dropped becomes its own singleton. */
    private List<List<String>> parseGroups(String raw, java.util.Set<String> knownIds) {
        var groups = new ArrayList<List<String>>();
        var placed = new java.util.HashSet<String>();
        try {
            var text = raw.trim();
            var start = text.indexOf('{');
            var end = text.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalStateException("no JSON object");
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            for (JsonNode groupNode : root.path("groups")) {
                var group = new ArrayList<String>();
                for (JsonNode idNode : groupNode) {
                    var id = idNode.asText("");
                    if (knownIds.contains(id) && placed.add(id)) group.add(id);
                }
                if (!group.isEmpty()) groups.add(group);
            }
        } catch (Exception ex) {
            log.warn("admin.product.ai_split.parse_failed raw='{}'", raw);
            groups.clear();
            placed.clear();
        }
        // Any id the AI never placed (or a failed parse) becomes its own singleton — EAN
        // cohesion then re-merges whatever shares a real GTIN. No item is ever lost.
        for (var id : knownIds) {
            if (placed.add(id)) groups.add(new ArrayList<>(List.of(id)));
        }
        return groups;
    }

    /**
     * Turns AI id-groups into an EAN-cohesive {@code label → items} map. EAN cohesion is
     * enforced here OVER the AI: any items sharing the same real GTIN are forced into ONE
     * group even if the AI split them (the shared real EAN is ground truth). Labels are
     * stable {@code grp:N} so the plan/apply round-trips consistently.
     */
    private Map<String, List<ReceiptItem>> toEanCohesiveGroups(List<List<String>> aiGroups,
                                                               Map<String, ReceiptItem> itemsById) {
        // Each AI group starts as a representative index; union groups that share a real EAN.
        var representativeByGroup = new int[aiGroups.size()];
        for (var index = 0; index < aiGroups.size(); index++) representativeByGroup[index] = index;
        var groupOfRealEan = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < aiGroups.size(); index++) {
            for (var id : aiGroups.get(index)) {
                var ean = itemsById.get(id).getEan();
                if (!EanAuthority.isReal(ean)) continue;
                var trimmedEan = ean.trim();
                var existing = groupOfRealEan.get(trimmedEan);
                if (existing == null) {
                    groupOfRealEan.put(trimmedEan, find(representativeByGroup, index));
                } else {
                    union(representativeByGroup, existing, index); // same real GTIN ⇒ merge
                }
            }
        }

        var merged = new LinkedHashMap<Integer, List<ReceiptItem>>();
        for (var index = 0; index < aiGroups.size(); index++) {
            var root = find(representativeByGroup, index);
            var bucket = merged.computeIfAbsent(root, key -> new ArrayList<>());
            for (var id : aiGroups.get(index)) bucket.add(itemsById.get(id));
        }

        var labelled = new LinkedHashMap<String, List<ReceiptItem>>();
        var label = 0;
        for (var bucket : merged.values()) {
            labelled.put("grp:" + label++, bucket);
        }
        return labelled;
    }

    // ── tiny union-find over group indices (EAN cohesion merges) ──

    private static int find(int[] representative, int index) {
        while (representative[index] != index) {
            representative[index] = representative[representative[index]];
            index = representative[index];
        }
        return index;
    }

    private static void union(int[] representative, int left, int right) {
        var leftRoot = find(representative, left);
        var rightRoot = find(representative, right);
        if (leftRoot != rightRoot) representative[Math.max(leftRoot, rightRoot)] = Math.min(leftRoot, rightRoot);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return new String(Hex.encode(digest.digest(value.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String abbrev(UUID id) {
        return id.toString().substring(0, 8);
    }

    private record SplitOutcome(ProductSplitPlan plan, int newProducts) {}
}
