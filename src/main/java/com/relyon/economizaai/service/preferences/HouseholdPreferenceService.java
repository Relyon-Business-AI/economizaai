package com.relyon.economizaai.service.preferences;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.BrandShare;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.BrandStrength;
import com.relyon.economizaai.dto.response.HouseholdPreferenceResponse.Confidence;
import com.relyon.economizaai.model.ManualBrandPreference;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 2.6 — derives per-household preferences (preferred pack size,
 * preferred brand) from confirmed purchase history.
 *
 * <p>Auto-derivation only — manual override is intentionally out of scope
 * (planned PRO feature). Volume-gated: a generic with fewer than
 * {@link CollaborativeProperties.Preferences#getMinPurchasesPerGeneric()}
 * purchases is silently skipped, so this stays empty until the household
 * has enough data for the signal to be meaningful.
 *
 * <p>Read-only and idempotent: nothing is persisted. The snapshot is
 * cheap enough to recompute per request at current volume; introduce a
 * cache or persisted projection only if profiling shows it matters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdPreferenceService {

    private final ReceiptItemRepository receiptItemRepository;
    private final ManualBrandPreferenceRepository manualBrandPreferenceRepository;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public List<HouseholdPreferenceResponse> derivePreferences(User user) {
        var preferences = properties.getPreferences();
        if (!preferences.isEnabled()) return List.of();

        var householdId = user.getHousehold().getId();
        var manualOverrides = manualBrandPreferenceRepository.findAllByHouseholdId(householdId).stream()
                .collect(Collectors.toMap(ManualBrandPreference::getGenericName,
                        preference -> preference, (first, duplicate) -> first));
        var history = receiptItemRepository.findConfirmedHistoryForHousehold(householdId);
        if (history.isEmpty() && manualOverrides.isEmpty()) return List.of();

        var byGeneric = history.stream()
                .filter(item -> genericKey(item) != null)
                .collect(Collectors.groupingBy(HouseholdPreferenceService::genericKey));

        var seen = new HashMap<String, HouseholdPreferenceResponse>();
        for (var entry : byGeneric.entrySet()) {
            if (entry.getValue().size() < preferences.getMinPurchasesPerGeneric()) continue;
            var derived = derivePreference(entry.getKey(), entry.getValue(), preferences);
            if (derived != null) seen.put(entry.getKey(), derived);
        }

        // Manual overrides win over derived: if the user explicitly set a brand
        // for "leite", we honor that even when history says otherwise. Manual
        // entries can also introduce generics not present in history at all
        // (e.g. they declare a future preference before having purchases).
        for (var override : manualOverrides.values()) {
            var derived = seen.get(override.getGenericName());
            seen.put(override.getGenericName(), applyManualBrand(derived, override));
        }

        var snapshot = seen.values().stream()
                .sorted(Comparator.comparing(HouseholdPreferenceResponse::genericName))
                .toList();

        log.info("preferences.derive.done household={} generics={} manual={} kept={}",
                householdId, byGeneric.size(), manualOverrides.size(), snapshot.size());
        return snapshot;
    }

    private HouseholdPreferenceResponse applyManualBrand(HouseholdPreferenceResponse derived,
                                                         ManualBrandPreference override) {
        if (derived == null) {
            // Manual-only entry: no purchase history yet. Pack-size fields
            // and distribution stay empty; sample size is 0.
            return new HouseholdPreferenceResponse(
                    override.getGenericName(),
                    null, null, null, null,
                    override.getBrand(),
                    override.getStrength(),
                    null,
                    List.of(),
                    0,
                    Confidence.LOW
            );
        }
        // Keep the derived pack-size + distribution + sample stats so the FE
        // can still show the underlying signal — only swap brand fields.
        return new HouseholdPreferenceResponse(
                derived.genericName(),
                derived.preferredPackSize(),
                derived.preferredPackUnit(),
                derived.minObservedPackSize(),
                derived.maxObservedPackSize(),
                override.getBrand(),
                override.getStrength(),
                shareForBrand(derived.brandDistribution(), override.getBrand()),
                derived.brandDistribution(),
                derived.sampleSize(),
                derived.confidence()
        );
    }

    private static BigDecimal shareForBrand(List<BrandShare> distribution, String brand) {
        if (distribution == null || brand == null) return null;
        return distribution.stream()
                .filter(share -> brand.equalsIgnoreCase(share.brand()))
                .map(BrandShare::share)
                .findFirst()
                .orElse(null);
    }

    private HouseholdPreferenceResponse derivePreference(String genericKey,
                                                         List<ReceiptItem> items,
                                                         CollaborativeProperties.Preferences cfg) {
        var packSize = derivePackSize(items);
        var brand = deriveBrand(items, cfg);
        if (packSize == null && brand == null) return null;

        return new HouseholdPreferenceResponse(
                genericKey,
                packSize != null ? packSize.preferredSize() : null,
                packSize != null ? packSize.preferredUnit() : null,
                packSize != null ? packSize.minSize() : null,
                packSize != null ? packSize.maxSize() : null,
                brand != null ? brand.topBrand() : null,
                brand != null ? brand.strength() : null,
                brand != null ? brand.topShare() : null,
                brand != null ? brand.distribution() : List.of(),
                items.size(),
                confidenceFor(items.size())
        );
    }

    private PackSizePreference derivePackSize(List<ReceiptItem> items) {
        var sizes = items.stream()
                .map(ReceiptItem::getProduct)
                .filter(product -> product.getPackSize() != null && product.getPackUnit() != null)
                .toList();
        if (sizes.isEmpty()) return null;

        var byPack = sizes.stream()
                .collect(Collectors.groupingBy(product -> product.getPackUnit(),
                        Collectors.toList()));
        var dominantUnit = byPack.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (dominantUnit == null) return null;

        var inDominantUnit = byPack.get(dominantUnit);
        var packSizes = inDominantUnit.stream().map(Product::getPackSize).toList();
        var preferred = mostFrequent(packSizes);
        var min = packSizes.stream().min(BigDecimal::compareTo).orElse(null);
        var max = packSizes.stream().max(BigDecimal::compareTo).orElse(null);
        return new PackSizePreference(preferred, dominantUnit, min, max);
    }

    private BrandPreference deriveBrand(List<ReceiptItem> items, CollaborativeProperties.Preferences cfg) {
        var brandCounts = items.stream()
                .map(ReceiptItem::getProduct)
                .map(Product::getBrand)
                .filter(brand -> brand != null && !brand.isBlank())
                .collect(Collectors.groupingBy(brand -> brand, Collectors.counting()));
        if (brandCounts.isEmpty()) return null;

        var totalWithBrand = brandCounts.values().stream().mapToLong(Long::longValue).sum();
        var sortedBrands = brandCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();
        var top = sortedBrands.get(0);
        var topShare = BigDecimal.valueOf(top.getValue())
                .divide(BigDecimal.valueOf(totalWithBrand), 4, RoundingMode.HALF_UP);

        BrandStrength strength;
        if (topShare.doubleValue() >= cfg.getMustHaveBrandShare()) {
            strength = BrandStrength.MUST_HAVE;
        } else if (topShare.doubleValue() >= cfg.getPreferredBrandShare()) {
            strength = BrandStrength.PREFERRED;
        } else {
            return null;
        }

        var distribution = sortedBrands.stream()
                .map(entry -> new BrandShare(
                        entry.getKey(),
                        BigDecimal.valueOf(entry.getValue())
                                .divide(BigDecimal.valueOf(totalWithBrand), 4, RoundingMode.HALF_UP),
                        entry.getValue().intValue()))
                .toList();
        return new BrandPreference(top.getKey(), strength, topShare, distribution);
    }

    private Confidence confidenceFor(int sampleSize) {
        if (sampleSize >= 15) return Confidence.HIGH;
        if (sampleSize >= 8) return Confidence.MEDIUM;
        return Confidence.LOW;
    }

    private static String genericKey(ReceiptItem item) {
        var product = item.getProduct();
        if (product == null) return null;
        if (product.getGenericName() != null && !product.getGenericName().isBlank()) {
            return product.getGenericName();
        }
        return product.getNormalizedName();
    }

    private static <T> T mostFrequent(List<T> values) {
        return values.stream()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private record PackSizePreference(BigDecimal preferredSize, String preferredUnit,
                                      BigDecimal minSize, BigDecimal maxSize) {}

    private record BrandPreference(String topBrand, BrandStrength strength,
                                   BigDecimal topShare, List<BrandShare> distribution) {}
}
