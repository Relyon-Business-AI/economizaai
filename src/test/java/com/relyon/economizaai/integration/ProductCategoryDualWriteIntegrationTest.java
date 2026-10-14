package com.relyon.economizaai.integration;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Validates the dual-write bridge end-to-end: persisting a Product with a legacy
 * enum category makes the {@link com.relyon.economizaai.model.listener.ProductCategoryNodeListener}
 * mirror it into {@code category_id} from the seeded categories tree. (The H2
 * test schema has no Flyway seed, so categories are inserted here explicitly.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Transactional
class ProductCategoryDualWriteIntegrationTest {

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryResolver categoryResolver;

    @Test
    void persistingProduct_mirrorsEnumIntoCategoryId() {
        var mercearia = categoryRepository.save(Category.builder()
                .slug("mercearia").namePt("Mercearia").nameEn("Groceries")
                .legacyEnum(ProductCategory.GROCERIES).sortOrder(50).active(true).build());
        categoryRepository.flush();
        categoryResolver.warmUp(); // warm the in-memory cache outside any product flush

        var product = productRepository.saveAndFlush(Product.builder()
                .normalizedName("ARROZ TIO J 5KG").category(ProductCategory.GROCERIES).build());

        assertEquals(mercearia.getId(), product.getCategoryId(),
                "listener should mirror GROCERIES enum into the mercearia category id");
    }

    @Test
    void persistingProduct_withNullCategory_leavesCategoryIdNull() {
        categoryResolver.warmUp();
        var product = productRepository.saveAndFlush(Product.builder()
                .normalizedName("ITEM SEM CATEGORIA").build());
        assertNull(product.getCategoryId(), "no enum category → no node");
    }
}
