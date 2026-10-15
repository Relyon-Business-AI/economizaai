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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCategoryNodeListenerTest {

    @Mock private CategoryResolver categoryResolver;
    private final ProductCategoryNodeListener listener = new ProductCategoryNodeListener();

    @BeforeEach
    void wireResolver() {
        listener.wire(categoryResolver);
    }

    @Test
    void reconcilesCategoryNodeOnSave() {
        var leaf = UUID.randomUUID();
        var group = UUID.randomUUID();
        var product = Product.builder().category(ProductCategory.CLEANING).categoryId(leaf).build();
        when(categoryResolver.reconcileNode(ProductCategory.CLEANING, leaf)).thenReturn(group);

        listener.reconcileCategoryNode(product);

        assertEquals(group, product.getCategoryId(), "listener writes the reconciled node back onto the product");
    }

    @Test
    void clearsNodeWhenEnumNull() {
        var product = Product.builder().category(null).categoryId(UUID.randomUUID()).build();
        when(categoryResolver.reconcileNode(null, product.getCategoryId())).thenReturn(null);

        listener.reconcileCategoryNode(product);

        assertNull(product.getCategoryId());
    }
}
