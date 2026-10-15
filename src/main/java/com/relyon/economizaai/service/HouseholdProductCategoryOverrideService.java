package com.relyon.economizaai.service;

import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
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
    private final HouseholdCacheGen householdCacheGen;

    /** Upsert: point this household's view of the product at a global enum category. */
    @Transactional
    public void setOverride(User user, Product product, ProductCategory category) {
        var override = loadOrCreate(user, product);
        override.setCategory(category);
        override.setCustomCategory(null);
        override.setCategoryNodeId(null);
        overrideRepository.save(override);
        householdCacheGen.bump(user.getHousehold().getId()); // insights são override-aware → invalida o cache de gastos
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
        householdCacheGen.bump(user.getHousehold().getId());
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
        householdCacheGen.bump(user.getHousehold().getId());
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

    /**
     * Per-product EFFECTIVE category for the override-aware "Por categoria" chart.
     * Resolves each override to a taxonomy node (node/enum corrections) or a custom
     * bucket (carrying the group it nests under + its visual). Products without an
     * override are absent from the map → the caller falls back to the global node.
     */
    @Transactional(readOnly = true)
    public Map<UUID, EffectiveCategory> effectiveCategoryByProduct(UUID householdId, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        var locale = LocaleContextHolder.getLocale();
        var map = new HashMap<UUID, EffectiveCategory>();
        for (var override : overrideRepository.findByHouseholdIdAndProductIdIn(householdId, productIds)) {
            var effective = toEffective(override, locale);
            if (effective != null) map.put(override.getProduct().getId(), effective);
        }
        return map;
    }

    private EffectiveCategory toEffective(HouseholdProductCategoryOverride override, Locale locale) {
        if (override.getCategoryNodeId() != null) {
            var slug = categoryResolver.slugForId(override.getCategoryNodeId());
            var label = categoryResolver.labelFor(override.getCategoryNodeId(), locale);
            return slug == null ? null : new EffectiveCategory(slug, slug, label, null, null, null);
        }
        var custom = override.getCustomCategory();
        if (custom != null) {
            return new EffectiveCategory("custom:" + custom.getId(), "custom:" + custom.getId(),
                    custom.getName(), customParentSlug(custom), custom.getIcon(), custom.getColor());
        }
        var enumCategory = override.getCategory();
        if (enumCategory != null) {
            var nodeId = categoryResolver.idForLegacyEnum(enumCategory);
            var slug = nodeId != null ? categoryResolver.slugForId(nodeId) : null;
            var label = nodeId != null ? categoryResolver.labelFor(nodeId, locale) : enumCategory.name();
            return new EffectiveCategory(slug != null ? slug : enumCategory.name(), slug, label, null, null, null);
        }
        return null;
    }

    /** A custom category's parent key: built-in slug, or {@code custom:<id>} when nested
     *  under another custom category, or null when it's top-level. */
    private static String customParentSlug(HouseholdCustomCategory custom) {
        if (custom.getParentCategory() != null) return custom.getParentCategory().getSlug();
        if (custom.getParentCustomCategory() != null) return "custom:" + custom.getParentCustomCategory().getId();
        return null;
    }

    /**
     * The household's effective category for a product. {@code key} is the collision-free
     * bucket key; {@code slug} is the taxonomy slug (null for a custom category). For
     * customs, {@code parentSlug}/{@code icon}/{@code color} carry the nesting + visual.
     */
    public record EffectiveCategory(String key, String slug, String label,
                                    String parentSlug, String icon, String color) {}
}
