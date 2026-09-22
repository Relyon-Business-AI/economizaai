package com.relyon.economizaai.service;

import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Upsert: point this household's view of the product at a global enum category. */
    @Transactional
    public void setOverride(User user, Product product, ProductCategory category) {
        var override = loadOrCreate(user, product);
        override.setCategory(category);
        override.setCustomCategory(null);
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
        overrideRepository.save(override);
        log.info("category.override.set household={} product={} customCategory={}",
                user.getHousehold().getId(), product.getId(), customCategory.getId());
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

    /** Map of productId → effective category LABEL (custom name or enum name) for a household. */
    @Transactional(readOnly = true)
    public Map<UUID, String> overridesByProduct(UUID householdId, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return overrideRepository.findByHouseholdIdAndProductIdIn(householdId, productIds).stream()
                .filter(override -> override.effectiveLabel() != null)
                .collect(Collectors.toMap(o -> o.getProduct().getId(), HouseholdProductCategoryOverride::effectiveLabel));
    }

    /**
     * Map of productId → effective category KEY+LABEL for a household. The key is
     * discriminated ({@code custom:<id>} / {@code enum:<name>}) so spend-bucket
     * accumulators never merge a custom category with an enum that shares its label.
     */
    @Transactional(readOnly = true)
    public Map<UUID, OverrideKey> overrideKeysByProduct(UUID householdId, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return overrideRepository.findByHouseholdIdAndProductIdIn(householdId, productIds).stream()
                .filter(override -> override.effectiveKey() != null)
                .collect(Collectors.toMap(
                        override -> override.getProduct().getId(),
                        override -> new OverrideKey(override.effectiveKey(), override.effectiveLabel())));
    }

    /** A product's effective category as a discriminated key plus its display label. */
    public record OverrideKey(String key, String label) {}
}
