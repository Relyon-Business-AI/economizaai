package com.relyon.economizaai.model.listener;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.service.CategoryResolver;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single enforcement point for the taxonomy invariant: a product's fine
 * {@link Product#getCategoryId() categoryId} (two-level node) must never
 * contradict its coarse {@link Product#getCategory() category} (legacy enum/group).
 *
 * <p>Fires on every persist/update so EVERY write path — user edits, consensus
 * graduation, LLM/AI corrections, pharmacy defaults, admin assignment — gets the
 * node reconciled to the enum's group (a finer leaf under the right group is kept;
 * a stale leaf from another group collapses to the group; a null enum clears it).
 * This is why {@link CategoryResolver} is deliberately query-free: it is safe to
 * call mid-flush.
 *
 * <p>The resolver is injected optionally via a setter (not the constructor): JPA entity
 * listeners are instantiated by Hibernate, not always as Spring beans (e.g. {@code @DataJpaTest}
 * slices that don't load services). In the full application context Spring supplies the managed
 * singleton (wired); in a slice without the service the resolver stays null and the listener
 * no-ops rather than failing the SessionFactory build.
 */
@Component
public class ProductCategoryNodeListener {

    private CategoryResolver categoryResolver;

    @Autowired(required = false)
    void wire(CategoryResolver resolver) {
        this.categoryResolver = resolver;
    }

    @PrePersist
    @PreUpdate
    void reconcileCategoryNode(Product product) {
        if (categoryResolver == null) return;
        product.setCategoryId(categoryResolver.reconcileNode(product.getCategory(), product.getCategoryId()));
    }
}
