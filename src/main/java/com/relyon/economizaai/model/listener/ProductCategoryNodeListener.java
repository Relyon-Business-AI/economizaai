package com.relyon.economizaai.model.listener;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.service.CategoryResolver;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single enforcement point for the taxonomy invariant — FASE 2 (nó é a verdade): o
 * {@link Product#getCategoryId() categoryId} (nó da taxonomia) manda; o
 * {@link Product#getCategory() category} (enum legado) é uma PROJEÇÃO derivada dele.
 *
 * <p>Fires on every persist/update:
 * <ul>
 *   <li>produto COM nó → o enum é derivado do nó ({@link CategoryResolver#deriveCoarseEnum}:
 *       enum da categoria do nó; categoria sem enum legado mantém o enum atual, senão OTHER);</li>
 *   <li>produto SEM nó mas com enum → ganha o nó-grupo do enum (piso — mantém escritores
 *       legados funcionando até migrarem).</li>
 * </ul>
 * Quem escreve o ENUM como intenção (admin coarse, LLM enrich, consenso, farmácia) deve mover o
 * nó junto via {@link CategoryResolver#reconcileNode} — senão o nó atual vence e re-deriva o enum.
 * {@link CategoryResolver} is deliberately query-free: it is safe to call mid-flush.
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
        if (product.getCategoryId() != null) {
            product.setCategory(categoryResolver.deriveCoarseEnum(product.getCategoryId(), product.getCategory()));
        } else if (product.getCategory() != null) {
            product.setCategoryId(categoryResolver.idForLegacyEnum(product.getCategory()));
        }
    }
}
