package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.CreateCategoryRequest;
import com.relyon.economizaai.dto.response.CategoryMigrationResponse;
import com.relyon.economizaai.dto.response.CategoryResponse;
import com.relyon.economizaai.dto.response.TaxonomyCategoryResponse;
import com.relyon.economizaai.exception.CustomCategoryNotFoundException;
import com.relyon.economizaai.exception.InvalidCategoryMigrationException;
import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.HouseholdCustomCategory;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final CategoryRepository categoryRepository;

    /** The active two-level taxonomy tree (groups + leaves), localized, for the FE. */
    @Transactional(readOnly = true)
    public List<TaxonomyCategoryResponse> taxonomy() {
        var locale = LocaleContextHolder.getLocale();
        var all = categoryRepository.findAllByActiveTrueOrderBySortOrderAsc();
        Set<UUID> parentIds = all.stream()
                .map(Category::getParent)
                .filter(parent -> parent != null)
                .map(Category::getId)
                .collect(Collectors.toSet());
        return all.stream()
                .map(category -> new TaxonomyCategoryResponse(
                        category.getSlug(),
                        localizedName(category, locale),
                        category.getParent() != null ? category.getParent().getSlug() : null,
                        parentIds.contains(category.getId())))
                .toList();
    }

    private static String localizedName(Category category, Locale locale) {
        return locale != null && "en".equals(locale.getLanguage()) ? category.getNameEn() : category.getNamePt();
    }

    /**
     * Create (or, idempotent on name, update) a custom category for the household.
     * Optional visual (icon/color) and nesting under a taxonomy GROUP are applied
     * when provided — so re-submitting an existing name edits its style/group.
     */
    @Transactional
    public CategoryResponse create(User user, CreateCategoryRequest request) {
        var name = request.name().trim();
        var householdId = user.getHousehold().getId();
        var parentGroup = resolveParentGroup(request.parentGroupSlug());
        var existing = customCategoryRepository.findByHouseholdIdAndNameIgnoreCase(householdId, name);
        var category = existing.orElseGet(
                () -> HouseholdCustomCategory.builder().household(user.getHousehold()).name(name).build());
        var mutated = existing.isEmpty();
        if (request.icon() != null) { category.setIcon(request.icon()); mutated = true; }
        if (request.color() != null) { category.setColor(request.color()); mutated = true; }
        if (request.parentGroupSlug() != null) { category.setParentCategory(parentGroup); mutated = true; }
        var saved = mutated ? customCategoryRepository.save(category) : category;
        log.info("custom_category.create household={} name='{}' id={} parent={} icon={}",
                householdId, name, saved.getId(), parentGroup != null ? parentGroup.getSlug() : null, saved.getIcon());
        return toResponse(saved);
    }

    /** Resolve a taxonomy GROUP by slug (must be a top-level node). Null slug → standalone. */
    private Category resolveParentGroup(String slug) {
        if (slug == null || slug.isBlank()) return null;
        var category = categoryRepository.findBySlug(slug).orElseThrow(InvalidCategoryMigrationException::new);
        if (category.getParent() != null) throw new InvalidCategoryMigrationException(); // só grupo, não folha
        return category;
    }

    private CategoryResponse toResponse(HouseholdCustomCategory custom) {
        var parent = custom.getParentCategory();
        var locale = LocaleContextHolder.getLocale();
        return new CategoryResponse(custom.getId(), custom.getName(), true,
                custom.getIcon(), custom.getColor(),
                parent != null ? parent.getSlug() : null,
                parent != null ? localizedName(parent, locale) : null);
    }

    /** All categories the household can use: global enums + its custom categories. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list(User user) {
        var categories = new ArrayList<CategoryResponse>();
        for (var category : ProductCategory.values()) {
            categories.add(CategoryResponse.enumCategory(category.name()));
        }
        customCategoryRepository.findByHouseholdIdOrderByName(user.getHousehold().getId())
                .forEach(custom -> categories.add(toResponse(custom)));
        return categories;
    }

    /** Resolve a custom category owned by this household, or 404. */
    @Transactional(readOnly = true)
    public HouseholdCustomCategory requireOwned(User user, UUID id) {
        return customCategoryRepository.findByIdAndHouseholdId(id, user.getHousehold().getId())
                .orElseThrow(CustomCategoryNotFoundException::new);
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
