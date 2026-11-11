package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.response.ProductVariantSplitResponse;
import com.relyon.economizaai.dto.response.ProductVariantSplitResponse.ProductSplitPlan;
import com.relyon.economizaai.dto.response.ProductVariantSplitResponse.ResultingProduct;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductAliasRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.canonicalization.EanAuthority;
import com.relyon.economizaai.service.canonicalization.VariantDiscriminator;
import com.relyon.economizaai.service.extraction.BrandExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-time, reviewable cleanup that SPLITS mis-merged products — rows the OLD
 * name/type matcher collapsed into one {@code products} row even though their
 * items are DIFFERENT variants/brands/flavours (the EAN-authority rule that now
 * prevents this was added after the fact; this repairs the backlog).
 *
 * <p><b>Signature.</b> Each linked item's VARIANT SIGNATURE reuses the existing
 * {@link VariantDiscriminator} (strong tokens like {@code %}/zero/diet/sem glúten
 * + weak flavour tokens) PLUS the item's brand (extracted via {@link
 * BrandExtractor} — the discriminator itself is brand-blind). Items that share a
 * signature stay together, so legitimate "same product, multiple EANs" cases
 * (e.g. "Água Crystal sem gás 500ml" under two GTINs) remain ONE product: same
 * brand, no distinguishing token ⇒ one signature ⇒ not split. Different brand
 * (Nestlé vs Dália) or different flavour (12 Tang flavours) ⇒ many signatures ⇒
 * split.
 *
 * <p><b>What a split touches</b>, all keyed by {@code product_id} so the fix is
 * global, not one-screen: {@code receipt_items} (reassigned per signature),
 * {@code price_observations} (traced observation→audit→receipt→item by
 * receipt+unitPrice+quantity, then reassigned; untraceable ones stay on the
 * original and are counted), and {@code product_aliases} (reassigned by the
 * alias description's own signature). The original product row SURVIVES, keeping
 * the anchor signature, so the per-household / manual tables that reference it
 * ({@code household_product_snapshot}, {@code household_product_aliases},
 * {@code manual_purchases}, {@code shopping_list_items}, {@code consumption_snoozes})
 * keep a VALID FK and are intentionally left in place — they carry no variant
 * signature to route by, and the snapshot is a disposable price cache rebuilt on
 * the next consult. {@code generic_name_norm} is copied UNCHANGED onto every new
 * product so the type grouping ("ver todos os cremes de leite") still lists them
 * all.
 *
 * <p><b>Idempotent.</b> A product with a single signature is skipped, so clean
 * data and the legitimate keep-cases are untouched; re-running after a clean run
 * does nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductVariantSplitService {

    private static final int DEFAULT_MAX_PRODUCTS = 500;
    private static final int HARD_CAP = 2000;

    private final ProductRepository productRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ProductAliasRepository aliasRepository;
    private final BrandExtractor brandExtractor;

    /**
     * Plans (dryRun) or applies the split over products whose confirmed items carry
     * more than one distinct EAN (the cheap SQL pre-filter). dryRun mutates nothing.
     */
    @Transactional
    public ProductVariantSplitResponse splitVariants(int maxProducts, boolean dryRun) {
        var cap = Math.max(1, Math.min(maxProducts <= 0 ? DEFAULT_MAX_PRODUCTS : maxProducts, HARD_CAP));
        var candidateIds = receiptItemRepository.findProductIdsWithMultipleItemEans(PageRequest.of(0, cap));

        var plans = new ArrayList<ProductSplitPlan>();
        var newProductsCreated = 0;
        for (var productId : candidateIds) {
            var plan = planFor(productId);
            if (plan == null) continue; // single signature — nothing to split
            if (!dryRun) {
                newProductsCreated += applySplit(productId, plan);
            } else {
                newProductsCreated += (int) plan.resultingProducts().stream().filter(group -> !group.anchor()).count();
            }
            plans.add(plan);
        }

        log.info("admin.product.split_variants done dryRun={} candidates={} split={} newProducts={}",
                dryRun, candidateIds.size(), plans.size(), newProductsCreated);
        return new ProductVariantSplitResponse(dryRun, candidateIds.size(), plans.size(), newProductsCreated, plans);
    }

    /** Groups a product's items by signature. Returns null when there's a single signature. */
    private ProductSplitPlan planFor(UUID productId) {
        var product = productRepository.findById(productId).orElse(null);
        if (product == null) return null;

        var items = receiptItemRepository.findAllLinkedWithReceipt(productId);
        if (items.isEmpty()) return null;

        // Signature -> items, insertion-ordered for a stable report.
        var itemsBySignature = new LinkedHashMap<String, List<ReceiptItem>>();
        for (var item : items) {
            itemsBySignature.computeIfAbsent(signatureOf(item.getRawDescription()), key -> new ArrayList<>()).add(item);
        }
        if (itemsBySignature.size() < 2) return null; // consistent — idempotent skip

        var anchorSignature = pickAnchorSignature(product, itemsBySignature);
        var resulting = new ArrayList<ResultingProduct>();
        resulting.add(describeGroup(anchorSignature, itemsBySignature.get(anchorSignature), true, null));
        itemsBySignature.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(anchorSignature))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> resulting.add(describeGroup(entry.getKey(), entry.getValue(), false, null)));

        return new ProductSplitPlan(productId, product.getNormalizedName(),
                product.getGenericNameNorm(), anchorSignature, resulting);
    }

    /**
     * The signature that stays on the original row: prefer the group containing the
     * product's own real EAN (so the original keeps its identity), else the largest
     * group; ties broken by signature string for determinism.
     */
    private String pickAnchorSignature(Product product, Map<String, List<ReceiptItem>> itemsBySignature) {
        var productEan = product.getEan();
        if (EanAuthority.isReal(productEan)) {
            for (var entry : itemsBySignature.entrySet()) {
                var hasProductEan = entry.getValue().stream()
                        .anyMatch(item -> productEan.trim().equals(trimmed(item.getEan())));
                if (hasProductEan) return entry.getKey();
            }
        }
        return itemsBySignature.entrySet().stream()
                .max(Comparator.<Map.Entry<String, List<ReceiptItem>>>comparingInt(entry -> entry.getValue().size())
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private ResultingProduct describeGroup(String signature, List<ReceiptItem> groupItems, boolean anchor, UUID newProductId) {
        var distinctEans = groupItems.stream()
                .map(ReceiptItem::getEan)
                .filter(EanAuthority::isReal)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        var representativeEan = distinctEans.isEmpty() ? null : mostCommonRealEan(groupItems);
        var representativeName = representativeName(groupItems);
        var brand = brandExtractor.find(representativeName);
        var observationGroups = countObservationsFor(groupItems);
        var aliasGroups = countAliasesFor(groupItems.get(0).getProduct(), signature);
        return new ResultingProduct(signature, anchor, newProductId, brand, representativeEan, representativeName,
                distinctEans, groupItems.size(), observationGroups.traced(), aliasGroups,
                observationGroups.untraced());
    }

    /** Creates a product per non-anchor signature and reassigns that signature's rows. */
    private int applySplit(UUID productId, ProductSplitPlan plan) {
        var original = productRepository.findById(productId).orElseThrow();
        var items = receiptItemRepository.findAllLinkedWithReceipt(productId);
        var itemsBySignature = new LinkedHashMap<String, List<ReceiptItem>>();
        for (var item : items) {
            itemsBySignature.computeIfAbsent(signatureOf(item.getRawDescription()), key -> new ArrayList<>()).add(item);
        }

        var aliases = aliasRepository.findByProduct(original);
        var created = 0;
        for (var group : plan.resultingProducts()) {
            if (group.anchor()) continue;
            var groupItems = itemsBySignature.getOrDefault(group.signature(), List.of());
            if (groupItems.isEmpty()) continue; // defensive: nothing to move

            var newProduct = createSplitProduct(original, group);
            reassignItems(groupItems, newProduct);
            reassignObservations(groupItems, newProduct);
            reassignAliases(aliases, group.signature(), newProduct);
            created++;
            log.info("admin.product.split_variants.split original={} newProduct={} signature='{}' ean={} items={} observations={} aliases={}",
                    productId, newProduct.getId(), group.signature(), newProduct.getEan(),
                    group.itemCount(), group.observationCount(), group.aliasCount());
        }
        return created;
    }

    /** New row inherits the type/category/pack of the original; brand + EAN + name come from the group. */
    private Product createSplitProduct(Product original, ResultingProduct group) {
        var ean = uniqueOrNull(group.representativeEan());
        var product = Product.builder()
                .ean(ean)
                .normalizedName(group.representativeName())
                .genericName(original.getGenericName())
                .genericNameNorm(original.getGenericNameNorm()) // type grouping must NOT change
                .brand(group.brand())
                .brandNorm(DescriptionNormalizer.normalizeOrNull(group.brand()))
                .category(original.getCategory())
                .categoryId(original.getCategoryId())
                .unit(original.getUnit())
                .packSize(original.getPackSize())
                .packUnit(original.getPackUnit())
                .categorizationSource(original.getCategorizationSource())
                .build();
        return productRepository.save(product);
    }

    private void reassignItems(List<ReceiptItem> groupItems, Product target) {
        var ids = groupItems.stream().map(ReceiptItem::getId).toList();
        receiptItemRepository.repointItemsTo(ids, target);
    }

    /**
     * Traces each observation to its source item (audit→receiptId + unitPrice + quantity)
     * and reassigns the ones whose source item is in this group. Untraceable observations
     * stay on the original product (counted in the plan).
     */
    private void reassignObservations(List<ReceiptItem> groupItems, Product target) {
        var originalId = groupItems.get(0).getProduct().getId();
        var keys = itemKeys(groupItems);
        var observationIds = new ArrayList<UUID>();
        for (var row : priceObservationRepository.findObservationTraceByProduct(originalId)) {
            var observationId = (UUID) row[0];
            var receiptId = (UUID) row[1];
            var unitPrice = (BigDecimal) row[2];
            var quantity = (BigDecimal) row[3];
            if (receiptId == null) continue;
            if (keys.contains(traceKey(receiptId, unitPrice, quantity))) observationIds.add(observationId);
        }
        if (!observationIds.isEmpty()) priceObservationRepository.repointObservationsTo(observationIds, target);
    }

    private void reassignAliases(List<ProductAlias> aliases, String signature, Product target) {
        var ids = aliases.stream()
                .filter(alias -> signatureOf(alias.getRawDescription()).equals(signature))
                .map(ProductAlias::getId)
                .toList();
        if (!ids.isEmpty()) aliasRepository.repointAliasesTo(ids, target);
    }

    // ── signature + tracing helpers ──

    /** Variant signature: brand ∪ VariantDiscriminator tokens. Brand first so brand alone splits. */
    private String signatureOf(String rawDescription) {
        var brand = brandExtractor.find(rawDescription);
        var brandKey = brand == null ? "" : DescriptionNormalizer.normalizeOrNull(brand);
        var tokens = String.join(",", VariantDiscriminator.signature(rawDescription));
        return "brand=" + (brandKey == null ? "" : brandKey) + "|tokens=" + tokens;
    }

    private ObservationCounts countObservationsFor(List<ReceiptItem> groupItems) {
        var originalId = groupItems.get(0).getProduct().getId();
        var keys = itemKeys(groupItems);
        var traced = 0L;
        var untraced = 0L;
        for (var row : priceObservationRepository.findObservationTraceByProduct(originalId)) {
            var receiptId = (UUID) row[1];
            if (receiptId == null) {
                untraced++; // audit receipt gone (deleted account) — stays on the original product
                continue;
            }
            if (keys.contains(traceKey(receiptId, (BigDecimal) row[2], (BigDecimal) row[3]))) traced++;
        }
        return new ObservationCounts(traced, untraced);
    }

    private long countAliasesFor(Product original, String signature) {
        return aliasRepository.findByProduct(original).stream()
                .filter(alias -> signatureOf(alias.getRawDescription()).equals(signature))
                .count();
    }

    private Set<String> itemKeys(List<ReceiptItem> groupItems) {
        var keys = new HashSet<String>();
        for (var item : groupItems) {
            keys.add(traceKey(item.getReceipt().getId(), item.getUnitPrice(), item.getQuantity()));
        }
        return keys;
    }

    private static String traceKey(UUID receiptId, BigDecimal unitPrice, BigDecimal quantity) {
        return receiptId + "|" + stripScale(unitPrice) + "|" + stripScale(quantity);
    }

    private static String stripScale(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String mostCommonRealEan(List<ReceiptItem> groupItems) {
        var counts = new LinkedHashMap<String, Long>();
        for (var item : groupItems) {
            if (EanAuthority.isReal(item.getEan())) {
                counts.merge(item.getEan().trim(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** The most frequent raw description in the group — the new product's display/normalized name. */
    private static String representativeName(List<ReceiptItem> groupItems) {
        var counts = new LinkedHashMap<String, Long>();
        for (var item : groupItems) {
            counts.merge(item.getRawDescription(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(groupItems.get(0).getRawDescription());
    }

    /** Keeps {@code products.ean} UNIQUE: null when any product already owns this EAN (items keep their own ean). */
    private String uniqueOrNull(String ean) {
        if (ean == null) return null;
        return productRepository.findByEan(ean).isPresent() ? null : ean;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private record ObservationCounts(long traced, long untraced) {}
}
