package com.relyon.economizaai.model.listener;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.service.CategoryResolver;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dual-write bridge: keeps {@link Product#getCategoryId()} mirrored from the
 * legacy {@code category} enum on every persist/update, so the new taxonomy
 * column stays consistent without touching the ~dozen call sites that set the
 * enum. Spring-managed @EntityListeners are autowired by Spring Data JPA's bean
 * container; the null-guard keeps persistence working in slices/contexts where
 * the bean isn't available (e.g. @DataJpaTest), falling back to no-op.
 *
 * <p>Always mirrors the enum during the dual-write window (nothing writes leaf
 * nodes directly yet). When leaf-level refinement (EAN/OFF/LLM) lands, this rule
 * changes to fill-only so a refined leaf isn't clobbered.
 */
@Component
public class ProductCategoryNodeListener {

    private static CategoryResolver categoryResolver;

    @Autowired
    public void setCategoryResolver(CategoryResolver resolver) {
        ProductCategoryNodeListener.categoryResolver = resolver;
    }

    @PrePersist
    @PreUpdate
    public void syncCategoryNode(Product product) {
        if (categoryResolver == null) return; // bean not wired (e.g. JPA slice) → no-op
        product.setCategoryId(categoryResolver.idForLegacyEnum(product.getCategory()));
    }
}
