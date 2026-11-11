package com.relyon.economizaai.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.dto.response.AiProductVariantSplitResponse;
import com.relyon.economizaai.dto.response.AiProductVariantSplitResponse.SplitSafety;
import com.relyon.economizaai.dto.response.ProductVariantSplitResponse.ProductSplitPlan;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
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
 * <p><b>Two cohesion guards are enforced OVER the AI</b> (fail-closed, never overridable).
 * After the AI returns groups:
 * <ul>
 *   <li><b>EAN cohesion</b> — items sharing the same real GTIN ({@link EanAuthority#isReal})
 *       are merged into ONE group even if the AI split them; a shared real GTIN is ground
 *       truth.</li>
 *   <li><b>Identical-name cohesion</b> — items whose NORMALIZED raw description
 *       ({@link DescriptionNormalizer#normalize}) is identical are merged into ONE group even
 *       if the AI split them. Different barcodes are NOT reliable evidence of a different
 *       consumer product (a re-barcode is common; a real flavour the receipt name omits is
 *       indistinguishable from it), so when the names are the same we keep the items
 *       together — over-splitting legit same-product cases is the failure we guard against.</li>
 * </ul>
 * If the guards collapse everything back to a single group ⇒ no split.
 *
 * <p><b>Conservative by design.</b> The prompt tells the AI that different barcodes do NOT
 * mean different products and to separate items ONLY when the names clearly show a different
 * brand/flavour/variant — when in doubt, keep them together. The AI also returns a
 * {@code confidence} (HIGH/LOW) for the split.
 *
 * <p><b>Safe-apply gate.</b> On apply ({@code dryRun=false}) a proposed split is only
 * materialized when it is HIGH-confidence AND the separated groups have MATERIALLY DIFFERENT
 * names (the normalized representative names differ — a visible brand/flavour/variant
 * difference). Low-confidence or name-identical separations are DROPPED (items stay merged).
 * {@code dryRun=true} mutates nothing and reports, per product, whether the split is
 * SAFE-to-apply or SKIPPED (with the machine reason), so a human previews exactly what a
 * real apply would change.
 *
 * <p><b>Apply.</b> Accepted groups are applied through the EXISTING split mechanics (new
 * products created, {@code receipt_items} / {@code price_observations} / {@code product_aliases}
 * repointed, {@code generic_name_norm} preserved) via {@link ProductVariantSplitService#applyGroups}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProductVariantSplitService {

    private static final int DEFAULT_MAX = 50;
    private static final int HARD_CAP = 200;
    private static final int MAX_TOKENS = 1500;

    private static final String SYSTEM_PROMPT = """
            Você agrupa itens de cupom fiscal brasileiro que PODEM ter sido unidos sob um
            único produto. Cada item traz: id, texto bruto (raw), e o código de barras
            (ean, pode ser vazio).

            SEJA CONSERVADOR. Na dúvida, MANTENHA os itens NO MESMO grupo.

            Códigos de barras DIFERENTES NÃO significam produtos diferentes: é MUITO comum
            o mesmo produto ser recadastrado com um novo GTIN. NÃO use o ean para separar.

            Separe itens em grupos DIFERENTES SOMENTE quando os NOMES mostram claramente uma
            marca, sabor ou variante DIFERENTE (ex.: "CAFE MELITTA" vs "CAFE PIMPINELA";
            "TANG UVA" vs "TANG LARANJA"). Se os nomes são iguais (ignorando abreviações,
            grafias e tamanho/volume), é o MESMO produto — mantenha juntos. Itens com nome
            genérico igual e sem diferença visível de marca/sabor/variante ficam JUNTOS.

            Responda SOMENTE um JSON, sem texto fora dele, no formato:
            {"groups": [["id1","id2"], ["id3"]], "confidence": "HIGH"}
            onde cada array interno é um produto real distinto e contém os ids dos itens
            que pertencem a ele. Todo id recebido deve aparecer em exatamente um grupo.
            Se todos os itens forem o MESMO produto, retorne um único grupo com todos os ids.
            "confidence" é "HIGH" quando você tem certeza da separação pelos nomes, ou "LOW"
            quando está em dúvida (nesse caso prefira UM grupo só).
            """;

    private final ProductVariantSplitService deterministicSplit;
    private final ProductRepository productRepository;
    private final AiGateway aiGateway;
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Grouping cache keyed by product id + item-set hash, so a re-run doesn't re-pay the AI. */
    private final Map<String, AiClustering> groupingCache = new ConcurrentHashMap<>();

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
        var safety = new ArrayList<SplitSafety>();
        var newProductsCreated = 0;
        var processed = 0;
        for (var productId : candidateIds) {
            try {
                var outcome = processOne(productId, dryRun);
                processed++;
                if (outcome == null) continue; // product gone / fewer than two items — nothing to do
                if (dryRun && outcome.safety() != null) safety.add(outcome.safety());
                if (!outcome.safeToApply()) continue; // no split OR dropped by the safe-apply gate
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

        var skipped = (int) safety.stream().filter(verdict -> !verdict.safeToApply()).count();
        log.info("admin.product.ai_split done dryRun={} candidates={} processed={} split={} skipped={} newProducts={}",
                dryRun, candidateIds.size(), processed, plans.size(), skipped, newProductsCreated);
        return new AiProductVariantSplitResponse(true, dryRun, candidateIds.size(), plans.size(),
                newProductsCreated, plans, safety);
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

    /** One candidate: gather items → AI cluster → cohesion guards → plan → safe-apply gate → (optionally) apply. */
    private SplitOutcome processOne(UUID productId, boolean dryRun) {
        var product = productRepository.findById(productId).orElse(null);
        if (product == null) return null;
        var items = deterministicSplit.linkedItems(productId);
        if (items.size() < 2) return null;

        var itemsById = indexItems(items);
        var clustering = clusterWithAi(productId, itemsById); // untransacted outbound HTTP
        var groupsByLabel = toCohesiveGroups(clustering.groups(), itemsById);
        if (groupsByLabel.size() < 2) {
            log.info("admin.product.ai_split.no_split product={} groups={}", abbrev(productId), groupsByLabel.size());
            return dryRun
                    ? SplitOutcome.dropped(safety(product, groupsByLabel.size(), clustering.confidence(), Reason.NO_SPLIT))
                    : null;
        }

        var plan = deterministicSplit.planFromGroups(product, groupsByLabel);
        if (plan == null) return null;

        var reason = safeApplyReason(clustering.confidence(), plan);
        var proposed = (int) plan.resultingProducts().stream().filter(group -> !group.anchor()).count();
        if (reason != Reason.SAFE) {
            log.info("admin.product.ai_split.skipped product={} groups={} confidence={} reason={}",
                    abbrev(productId), groupsByLabel.size(), clustering.confidence(), reason);
            return dryRun
                    ? SplitOutcome.dropped(safety(product, groupsByLabel.size(), clustering.confidence(), reason))
                    : null;
        }

        if (dryRun) {
            return SplitOutcome.safe(plan, proposed,
                    safety(product, groupsByLabel.size(), clustering.confidence(), Reason.SAFE));
        }
        // Apply in a short transaction AFTER the AI call committed nothing — never hold a DB
        // connection across the outbound HTTP (CLAUDE.md).
        var created = transactionTemplate.execute(txStatus ->
                deterministicSplit.applyGroups(product, plan, groupsByLabel));
        return SplitOutcome.safe(plan, created == null ? 0 : created, null);
    }

    /**
     * The safe-apply gate. A proposed split is materialized ONLY when the AI was HIGH-confidence
     * AND the non-anchor groups carry a MATERIALLY DIFFERENT name from the anchor (their
     * normalized representative names differ). Anything else is dropped — the items stay merged.
     */
    private Reason safeApplyReason(String confidence, ProductSplitPlan plan) {
        if (!"HIGH".equalsIgnoreCase(confidence)) return Reason.LOW_CONFIDENCE;
        return namesMateriallyDifferent(plan) ? Reason.SAFE : Reason.NAMES_NOT_MATERIALLY_DIFFERENT;
    }

    /**
     * True when EVERY non-anchor group's normalized representative name differs from the anchor's
     * AND from every other group's — i.e. the split actually separates visibly distinct names,
     * not identical ones. A name-identical separation (same normalized name on both sides) is the
     * exact over-split the guard rejects, so it is NOT materially different.
     */
    private static boolean namesMateriallyDifferent(ProductSplitPlan plan) {
        var seen = new java.util.HashSet<String>();
        for (var group : plan.resultingProducts()) {
            if (!seen.add(normalizedName(group.representativeName()))) return false;
        }
        return true;
    }

    private static String normalizedName(String representativeName) {
        return DescriptionNormalizer.normalize(representativeName);
    }

    private SplitSafety safety(Product product, int proposedGroups, String confidence, Reason reason) {
        return new SplitSafety(product.getId(), product.getNormalizedName(), proposedGroups,
                confidence == null ? "LOW" : confidence.toUpperCase(), reason == Reason.SAFE, reason.name());
    }

    private enum Reason { SAFE, LOW_CONFIDENCE, NAMES_NOT_MATERIALLY_DIFFERENT, NO_SPLIT }

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
     * Calls the AI to cluster the distinct items, returning the groups (lists of item ids) and
     * the AI's confidence. Cached per product + item-set hash. On an unparseable response, falls
     * back to one group per item at LOW confidence — the cohesion guards then salvage whatever
     * shares a real GTIN or a normalized name, and the safe-apply gate drops the rest.
     */
    private AiClustering clusterWithAi(UUID productId, Map<String, ReceiptItem> itemsById) {
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
        var clustering = parseClustering(raw, itemsById.keySet());
        groupingCache.put(cacheKey, clustering);
        return clustering;
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

    /**
     * Parses {"groups":[[id,...],...], "confidence":"HIGH"}; keeps only known ids; any id the AI
     * dropped becomes its own singleton. A failed parse yields LOW confidence (so the safe-apply
     * gate never materializes a split from garbage).
     */
    private AiClustering parseClustering(String raw, java.util.Set<String> knownIds) {
        var groups = new ArrayList<List<String>>();
        var placed = new java.util.HashSet<String>();
        var confidence = "LOW";
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
            confidence = "HIGH".equalsIgnoreCase(root.path("confidence").asText("LOW")) ? "HIGH" : "LOW";
        } catch (Exception ex) {
            log.warn("admin.product.ai_split.parse_failed raw='{}'", raw);
            groups.clear();
            placed.clear();
            confidence = "LOW";
        }
        // Any id the AI never placed (or a failed parse) becomes its own singleton — the cohesion
        // guards then re-merge whatever shares a real GTIN or an identical normalized name. No
        // item is ever lost.
        for (var id : knownIds) {
            if (placed.add(id)) groups.add(new ArrayList<>(List.of(id)));
        }
        return new AiClustering(groups, confidence);
    }

    /**
     * Turns AI id-groups into a cohesive {@code label → items} map. TWO guards are enforced
     * here OVER the AI, both as union-find merges, fail-closed:
     * <ul>
     *   <li><b>EAN cohesion</b> — any items sharing the same real GTIN are forced into ONE group
     *       even if the AI split them (the shared real EAN is ground truth);</li>
     *   <li><b>Identical-name cohesion</b> — any items whose NORMALIZED raw description is
     *       identical are forced into ONE group even if the AI split them (different barcodes are
     *       NOT reliable evidence of a different product; the same normalized name is).</li>
     * </ul>
     * Labels are stable {@code grp:N} so the plan/apply round-trips consistently.
     */
    private Map<String, List<ReceiptItem>> toCohesiveGroups(List<List<String>> aiGroups,
                                                            Map<String, ReceiptItem> itemsById) {
        // Each AI group starts as its own representative index; the two passes below union groups.
        var representativeByGroup = new int[aiGroups.size()];
        for (var index = 0; index < aiGroups.size(); index++) representativeByGroup[index] = index;

        // Pass 1 — EAN cohesion: groups sharing a real GTIN merge.
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

        // Pass 2 — identical-name cohesion: groups sharing a normalized raw description merge.
        var groupOfNormalizedName = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < aiGroups.size(); index++) {
            for (var id : aiGroups.get(index)) {
                var normalizedName = DescriptionNormalizer.normalizeOrNull(itemsById.get(id).getRawDescription());
                if (normalizedName == null) continue; // no usable name ⇒ nothing to cohere on
                var existing = groupOfNormalizedName.get(normalizedName);
                if (existing == null) {
                    groupOfNormalizedName.put(normalizedName, find(representativeByGroup, index));
                } else {
                    union(representativeByGroup, existing, index); // same normalized name ⇒ merge
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

    // ── tiny union-find over group indices (EAN + identical-name cohesion merges) ──

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

    /** The AI's answer for one product: the groups and its confidence (HIGH/LOW). */
    private record AiClustering(List<List<String>> groups, String confidence) {}

    /**
     * One candidate's outcome. {@code safeToApply} gates whether the plan is materialized;
     * {@code safety} is the dry-run verdict (null on a real apply or when there was nothing
     * to report).
     */
    private record SplitOutcome(ProductSplitPlan plan, int newProducts, boolean safeToApply, SplitSafety safety) {
        static SplitOutcome safe(ProductSplitPlan plan, int newProducts, SplitSafety safety) {
            return new SplitOutcome(plan, newProducts, true, safety);
        }

        static SplitOutcome dropped(SplitSafety safety) {
            return new SplitOutcome(null, 0, false, safety);
        }
    }
}
