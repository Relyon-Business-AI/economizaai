package com.relyon.economizaai.model.listener;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.CategoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FASE 2 (nó é a verdade): com nó presente, o enum é DERIVADO dele; sem nó, o enum ainda
 * dá o piso (nó-grupo) pros escritores legados.
 */
@ExtendWith(MockitoExtension.class)
class ProductCategoryNodeListenerTest {

    @Mock private CategoryResolver categoryResolver;
    private final ProductCategoryNodeListener listener = new ProductCategoryNodeListener();

    @BeforeEach
    void wireResolver() {
        listener.wire(categoryResolver);
    }

    @Test
    void nodePresent_enumIsDerivedFromNode() {
        var leaf = UUID.randomUUID();
        var product = Product.builder().category(ProductCategory.GROCERIES).categoryId(leaf).build();
        when(categoryResolver.deriveCoarseEnum(leaf, ProductCategory.GROCERIES)).thenReturn(ProductCategory.PRODUCE);

        listener.reconcileCategoryNode(product);

        assertEquals(ProductCategory.PRODUCE, product.getCategory(), "o nó manda; o enum segue");
        assertEquals(leaf, product.getCategoryId(), "o nó nunca é alterado pelo listener");
    }

    @Test
    void noNode_enumFloorsTheGroupNode() {
        var group = UUID.randomUUID();
        var product = Product.builder().category(ProductCategory.CLEANING).categoryId(null).build();
        when(categoryResolver.idForLegacyEnum(ProductCategory.CLEANING)).thenReturn(group);

        listener.reconcileCategoryNode(product);

        assertEquals(group, product.getCategoryId(), "escritor legado (só enum) ganha o nó-grupo");
        assertEquals(ProductCategory.CLEANING, product.getCategory());
    }

    @Test
    void nothingSet_noops() {
        var product = Product.builder().build();

        listener.reconcileCategoryNode(product);

        assertNull(product.getCategory());
        assertNull(product.getCategoryId());
        verifyNoInteractions(categoryResolver);
    }

    @Test
    void unwiredResolver_noops() {
        var unwired = new ProductCategoryNodeListener();
        var leaf = UUID.randomUUID();
        var product = Product.builder().category(ProductCategory.GROCERIES).categoryId(leaf).build();

        unwired.reconcileCategoryNode(product);

        assertEquals(ProductCategory.GROCERIES, product.getCategory());
        assertEquals(leaf, product.getCategoryId());
    }
}
