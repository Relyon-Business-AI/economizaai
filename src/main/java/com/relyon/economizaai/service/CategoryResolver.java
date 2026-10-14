package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the new two-level {@link Category} tree. Categories are a static seed,
 * so the enum→node mapping is cached in memory. The cache is warmed at startup
 * (and refreshable) rather than lazily — the hot path is a per-product persist
 * hook ({@link com.relyon.economizaai.model.listener.ProductCategoryNodeListener})
 * where issuing a query mid-flush would be reentrant and unsafe. {@link #idForLegacyEnum}
 * therefore only reads the in-memory map; it never queries.
 */
@Service
@RequiredArgsConstructor
public class CategoryResolver {

    private final CategoryRepository categoryRepository;

    private volatile Map<ProductCategory, UUID> idByLegacyEnum = new ConcurrentHashMap<>();
    private volatile Map<String, UUID> idBySlug = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        var byEnum = new ConcurrentHashMap<ProductCategory, UUID>();
        var bySlug = new ConcurrentHashMap<String, UUID>();
        for (var category : categoryRepository.findAll()) {
            bySlug.put(category.getSlug(), category.getId());
            if (category.getLegacyEnum() != null) {
                byEnum.put(category.getLegacyEnum(), category.getId());
            }
        }
        idByLegacyEnum = byEnum;
        idBySlug = bySlug;
    }

    /** Category id the given legacy enum maps to by default, or null. Never queries. */
    public UUID idForLegacyEnum(ProductCategory legacyEnum) {
        return legacyEnum == null ? null : idByLegacyEnum.get(legacyEnum);
    }

    /** Category id for a taxonomy slug (group or leaf), or null. Never queries. */
    public UUID idForSlug(String slug) {
        return slug == null ? null : idBySlug.get(slug);
    }

    public Optional<Category> findById(UUID id) {
        return id == null ? Optional.empty() : categoryRepository.findById(id);
    }

    public Optional<Category> findBySlug(String slug) {
        return categoryRepository.findBySlug(slug);
    }
}
