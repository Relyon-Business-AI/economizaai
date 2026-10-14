package com.relyon.economizaai.service;

import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Household-scoped category corrections — "evidence, not truth". Storing a
 * correction does NOT mutate the global {@link Product}; it overrides only what
 * the correcting household sees. The target is either a global enum category or
 * a {@link HouseholdCustomCategory}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdProductCategoryOverrideService {

    private final HouseholdProductCategoryOverrideRepository overrideRepository;
    private final CategoryResolver categoryResolver;

    /** Upsert: point this household's view of the product at a global enum category. */
    @Transactional
    public void setOverride(User user, Product product, ProductCategory category) {
        var override = loadOrCreate(user, product);
        override.setCategory(category);
        override.setCustomCategory(null);
        override.setCategoryNodeId(null);
        overrideRepository.save(override);
        log.info("category.override.set household={} product={} category={}",
                user.getHousehold().getId(), product.getId(), category);
    }

    /** Upsert: point this household's view of the product at a custom category. */
    @Transactional
    public void setCustomOverride(User user, Product product, HouseholdCustomCategory customCategory) {
        var override = loadOrCreate(user, product);
        override.setCategory(null);
        override.setCustomCategory(customCategory);
        override.setCategoryNodeId(null);
        overrideRepository.save(override);
        log.info("category.override.set household={} product={} customCategory={}",
                user.getHousehold().getId(), product.getId(), customCategory.getId());
    }

    /** Upsert: point this household's view of the product at a new-taxonomy node (fine leaf/group). */
    @Transactional
    public void setNodeOverride(User user, Product product, UUID categoryNodeId) {
        var override = loadOrCreate(user, product);
        override.setCategory(null);
        override.setCustomCategory(null);
        override.setCategoryNodeId(categoryNodeId);
        overrideRepository.save(override);
        log.info("category.override.set household={} product={} node={}",
                user.getHousehold().getId(), product.getId(), categoryNodeId);
    }

    private HouseholdProductCategoryOverride loadOrCreate(User user, Product product) {
        return overrideRepository.findByHouseholdIdAndProductId(user.getHousehold().getId(), product.getId())
                .orElseGet(() -> HouseholdProductCategoryOverride.builder()
                        .household(user.getHousehold())
                        .product(product)
                        .build());
    }

    /** Product ids this household migrated into a custom category. */
    @Transactional(readOnly = true)
    public List<UUID> productIdsInCustomCategory(UUID householdId, UUID customCategoryId) {
        return overrideRepository.findByHouseholdIdAndCustomCategoryId(householdId, customCategoryId).stream()
                .map(override -> override.getProduct().getId())
                .toList();
    }

    /** Map of productId → effective category LABEL (custom name, node name, or enum name) for a household. */
    @Transactional(readOnly = true)
    public Map<UUID, String> overridesByProduct(UUID householdId, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        var locale = LocaleContextHolder.getLocale();
        var map = new HashMap<UUID, String>();
        for (var override : overrideRepository.findByHouseholdIdAndProductIdIn(householdId, productIds)) {
            var label = labelOf(override, locale);
            if (label != null) map.put(override.getProduct().getId(), label);
        }
        return map;
    }

    /** The override's display label: node name (resolved via the categories table) → custom/enum name. */
    private String labelOf(HouseholdProductCategoryOverride override, Locale locale) {
        if (override.getCategoryNodeId() != null) {
            return categoryResolver.labelFor(override.getCategoryNodeId(), locale);
        }
        return override.effectiveLabel();
    }

    /**
     * Map of productId → effective category KEY+LABEL for a household. The key is
     * discriminated ({@code custom:<id>} / {@code enum:<name>}) so spend-bucket
     * accumulators never merge a custom category with an enum that shares its label.
     */
    @Transactional(readOnly = true)
    public Map<UUID, OverrideKey> overrideKeysByProduct(UUID householdId, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        var locale = LocaleContextHolder.getLocale();
        var map = new HashMap<UUID, OverrideKey>();
        for (var override : overrideRepository.findByHouseholdIdAndProductIdIn(householdId, productIds)) {
            if (override.effectiveKey() == null) continue;
            map.put(override.getProduct().getId(), new OverrideKey(override.effectiveKey(), labelOf(override, locale)));
        }
        return map;
    }

    /** A product's effective category as a discriminated key plus its display label. */
    public record OverrideKey(String key, String label) {}
}
