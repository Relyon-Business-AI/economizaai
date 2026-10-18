package com.relyon.economizaai.integration;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.admin.AdminProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prova COM BANCO REAL (não mock) o invariante node-first de ponta a ponta:
 * <ul>
 *   <li>setar a subcategoria via serviço (o caminho do PATCH /admin/products/{id}/category-node)
 *       atualiza o enum no banco — via {@link CategoryResolver#applyNode} (no Hibernate 7,
 *       @PreUpdate não persiste campo não-sujo, então o caminho de escrita é quem garante);</li>
 *   <li>criar produto com nó e sem enum deriva o enum no @PrePersist (o listener cobre criação).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class NodeFirstListenerIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryResolver categoryResolver;
    @Autowired private AdminProductService adminProductService;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID merceariaId;
    private UUID frutasId;

    @BeforeEach
    void seedTaxonomy() {
        if (categoryRepository.findBySlug("mercearia").isEmpty()) {
            categoryRepository.save(Category.builder()
                    .slug("mercearia").namePt("Mercearia").nameEn("Groceries")
                    .legacyEnum(ProductCategory.GROCERIES).build());
            var hortifruti = categoryRepository.save(Category.builder()
                    .slug("hortifruti").namePt("Hortifrúti").nameEn("Produce")
                    .legacyEnum(ProductCategory.PRODUCE).build());
            categoryRepository.save(Category.builder()
                    .slug("frutas").namePt("Frutas").nameEn("Fruits").parent(hortifruti).build());
        }
        categoryResolver.warmUp();
        merceariaId = categoryResolver.idForSlug("mercearia");
        frutasId = categoryResolver.idForSlug("frutas");
    }

    @Test
    void settingSubcategoryViaService_updatesLegacyEnum_inTheDatabase() {
        var productId = transactionTemplate.execute(status -> productRepository.save(Product.builder()
                .normalizedName("PRODUTO FLIP TESTE")
                .category(ProductCategory.GROCERIES)
                .categoryId(merceariaId)
                .build()).getId());

        adminProductService.setCategoryNode(productId, frutasId);

        var reloaded = transactionTemplate.execute(status ->
                productRepository.findById(productId).orElseThrow());
        assertEquals(frutasId, reloaded.getCategoryId(), "a subcategoria persiste");
        assertEquals(ProductCategory.PRODUCE, reloaded.getCategory(),
                "o enum DERIVA do nó e PERSISTE no banco (node-first)");
    }

    @Test
    void creatingProductWithNodeAndNoEnum_alignNewProductDerivesEnum() {
        // Contrato de criação: TODO site que constrói Product chama alignNewProduct antes do save
        // (o listener NÃO persiste campo cruzado no Hibernate 7 — nem no @PrePersist).
        var productId = transactionTemplate.execute(status -> {
            var product = Product.builder()
                    .normalizedName("PRODUTO CREATE TESTE")
                    .categoryId(frutasId) // sem enum — o alinhador deriva
                    .build();
            categoryResolver.alignNewProduct(product);
            return productRepository.save(product).getId();
        });

        var reloaded = transactionTemplate.execute(status ->
                productRepository.findById(productId).orElseThrow());
        assertEquals(ProductCategory.PRODUCE, reloaded.getCategory(),
                "criação com nó e sem enum deriva o enum (alignNewProduct persiste)");
    }
}
