package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.CategoryMigrationResponse;
import com.relyon.economizaai.dto.response.CategoryResponse;
import com.relyon.economizaai.exception.CustomCategoryNotFoundException;
import com.relyon.economizaai.exception.InvalidCategoryMigrationException;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Household-defined custom categories (e.g. "Frutas") + migrating products into
 * a category. Custom categories overlay the global enum and only affect the
 * owning household (via {@link HouseholdProductCategoryOverrideService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomCategoryService {

    private final HouseholdCustomCategoryRepository customCategoryRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductCategoryOverrideService overrideService;

    /** Create a custom category for the household (idempotent on name). */
    @Transactional
    public CategoryResponse create(User user, String rawName) {
        var name = rawName.trim();
        var householdId = user.getHousehold().getId();
        var existing = customCategoryRepository.findByHouseholdIdAndNameIgnoreCase(householdId, name);
        var category = existing.orElseGet(() -> customCategoryRepository.save(
                HouseholdCustomCategory.builder().household(user.getHousehold()).name(name).build()));
        log.info("custom_category.create household={} name='{}' id={}", householdId, name, category.getId());
        return new CategoryResponse(category.getId(), category.getName(), true);
    }

    /** All categories the household can use: global enums + its custom categories. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list(User user) {
        var categories = new ArrayList<CategoryResponse>();
        for (var category : ProductCategory.values()) {
            categories.add(new CategoryResponse(null, category.name(), false));
        }
        customCategoryRepository.findByHouseholdIdOrderByName(user.getHousehold().getId())
                .forEach(custom -> categories.add(new CategoryResponse(custom.getId(), custom.getName(), true)));
        return categories;
    }

    @Transactional
    public void delete(User user, UUID id) {
        var category = customCategoryRepository.findByIdAndHouseholdId(id, user.getHousehold().getId())
                .orElseThrow(CustomCategoryNotFoundException::new);
        customCategoryRepository.delete(category); // overrides pointing here cascade-delete
        log.info("custom_category.delete household={} id={}", user.getHousehold().getId(), id);
    }

    /**
     * Move the selected products into the target category (global enum OR a
     * custom category) for this household. Exactly one target must be given.
     */
    @Transactional
    public CategoryMigrationResponse migrate(User user, List<UUID> productIds,
                                             ProductCategory targetCategory, UUID targetCustomCategoryId) {
        if ((targetCategory == null) == (targetCustomCategoryId == null)) {
            throw new InvalidCategoryMigrationException(); // need exactly one target
        }
        var customCategory = targetCustomCategoryId == null ? null
                : customCategoryRepository.findByIdAndHouseholdId(targetCustomCategoryId, user.getHousehold().getId())
                        .orElseThrow(CustomCategoryNotFoundException::new);

        var migrated = 0;
        var skipped = 0;
        for (var productId : productIds) {
            var product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                skipped++;
                continue;
            }
            if (customCategory != null) {
                overrideService.setCustomOverride(user, product, customCategory);
            } else {
                overrideService.setOverride(user, product, targetCategory);
            }
            migrated++;
        }
        log.info("custom_category.migrate household={} target={} migrated={} skipped={}",
                user.getHousehold().getId(),
                customCategory != null ? "custom:" + customCategory.getId() : targetCategory, migrated, skipped);
        return new CategoryMigrationResponse(migrated, skipped);
    }

    /** Product ids the household has migrated into a given custom category (for /items filter). */
    @Transactional(readOnly = true)
    public List<UUID> productIdsIn(User user, UUID customCategoryId) {
        return overrideService.productIdsInCustomCategory(user.getHousehold().getId(), customCategoryId);
    }
}
