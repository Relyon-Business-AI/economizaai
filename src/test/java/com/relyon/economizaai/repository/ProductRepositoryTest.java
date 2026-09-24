package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hand-written JPQL on {@link ProductRepository}: catalog search,
 * metadata dedup lookup, admin curation queries, and the pharmacy-backfill
 * merchant query (status/source/excluded filters).
 */
@DataJpaTest
@ActiveProfiles("test")
class ProductRepositoryTest {

    private static final String CNPJ_PHARMACY = "11222333000144";
    private static final String CNPJ_OTHER = "55666777000188";

    @Autowired private ProductRepository productRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReceiptRepository receiptRepository;

    private Product saveProduct(String name, String ean, String brand) {
        return productRepository.save(Product.builder()
                .ean(ean).normalizedName(name).brand(brand)
                .category(ProductCategory.GROCERIES).build());
    }

    // ---------------------------------------------------------- search

    @Test
    void search_matchesNameCaseInsensitivelyAndEanExactly() {
        saveProduct("Arroz Tio Joao", "111", "Tio Joao");
        saveProduct("Feijao Preto", "222", "Camil");
        saveProduct("Farinha de ARROZ", null, null);

        var byName = productRepository.search("arroz", PageRequest.of(0, 20)).getContent();
        assertEquals(Set.of("Arroz Tio Joao", "Farinha de ARROZ"),
                byName.stream().map(Product::getNormalizedName).collect(Collectors.toSet()));

        var byEan = productRepository.search("222", PageRequest.of(0, 20)).getContent();
        assertEquals(1, byEan.size());
        assertEquals("Feijao Preto", byEan.get(0).getNormalizedName());
    }

    @Test
    void search_nullQueryReturnsWholeCatalogOrderedByName() {
        var banana = saveProduct("Banana", null, null);
        var arroz = saveProduct("Arroz", null, null);
        var cafe = saveProduct("Cafe", null, null);
        var ownIds = Set.of(banana.getId(), arroz.getId(), cafe.getId());

        var all = productRepository.search(null, PageRequest.of(0, 100)).getContent();

        assertEquals(List.of("Arroz", "Banana", "Cafe"),
                all.stream()
                   .filter(p -> ownIds.contains(p.getId()))
                   .map(Product::getNormalizedName)
                   .toList());
    }

    // ---------------------------------------------------------- findByMetadata

    @Test
    void findByMetadata_matchesOnlyTheFullFourDimensionTuple() {
        var pseudoEanTwin = productRepository.save(metadataProduct("Arroz Camil 5kg", "Arroz", "Camil", "5.000", "KG"));
        productRepository.save(metadataProduct("Arroz Camil 1kg", "Arroz", "Camil", "1.000", "KG"));
        productRepository.save(metadataProduct("Arroz Tio Joao 5kg", "Arroz", "Tio Joao", "5.000", "KG"));

        var matches = productRepository.findByMetadata("arroz", "camil", new BigDecimal("5.000"), "KG");

        assertEquals(1, matches.size());
        assertEquals(pseudoEanTwin.getId(), matches.get(0).getId());
    }

    @Test
    void findByMetadata_dedupsAcrossAccentAndCaseViaNormColumns() {
        var accented = productRepository.save(metadataProduct("Cafe Pilao 500g", "Café", "Pilão", "500.000", "G"));
        var plain = productRepository.save(metadataProduct("CAFE PILAO 500G", "CAFE", "PILAO", "500.000", "G"));

        // Query with the normalized form; both accent/case variants must match.
        var matches = productRepository.findByMetadata("cafe", "pilao", new BigDecimal("500.000"), "G");

        assertEquals(Set.of(accented.getId(), plain.getId()),
                matches.stream().map(Product::getId).collect(Collectors.toSet()));
    }

    // ---------------------------------------------------------- findMissingBrand

    @Test
    void findMissingBrand_returnsNullAndEmptyBrandsOrderedByName() {
        var cenoura = saveProduct("Cenoura", null, null);
        saveProduct("Arroz Tio Joao", null, "Tio Joao");
        var batata = productRepository.save(Product.builder()
                .normalizedName("Batata").brand("").category(ProductCategory.PRODUCE).build());
        var missingBrandIds = Set.of(cenoura.getId(), batata.getId());

        var missing = productRepository.findMissingBrand(PageRequest.of(0, 20)).getContent();

        assertEquals(List.of("Batata", "Cenoura"),
                missing.stream()
                       .filter(p -> missingBrandIds.contains(p.getId()))
                       .map(Product::getNormalizedName)
                       .toList());
    }

    // ---------------------------------------------------------- findDuplicateCandidates

    @Test
    void findDuplicateCandidates_returnsOnlyProductsSharingTheFullTuple() {
        var duplicateA = productRepository.save(metadataProduct("Arroz Camil 5kg", "Arroz", "Camil", "5.000", "KG"));
        var duplicateB = productRepository.save(metadataProduct("ARROZ CAMIL TIPO1 5KG", "Arroz", "Camil", "5.000", "KG"));
        // Unique tuple — not a candidate.
        productRepository.save(metadataProduct("Feijao Camil 1kg", "Feijao", "Camil", "1.000", "KG"));
        // Missing a dimension — can't be deduped this way even if it collides on the rest.
        productRepository.save(metadataProduct("Arroz Camil sem marca 5kg", "Arroz", null, "5.000", "KG"));

        var candidates = productRepository.findDuplicateCandidates();

        assertEquals(Set.of(duplicateA.getId(), duplicateB.getId()),
                candidates.stream().map(Product::getId).collect(Collectors.toSet()));
        // Contiguous: both rows of the same profile come back adjacent.
        assertEquals(candidates.get(0).getGenericName(), candidates.get(1).getGenericName());
        assertEquals(candidates.get(0).getBrand(), candidates.get(1).getBrand());
    }

    // ---------------------------------------------------------- findOtherCategoryProductsByMerchant

    @Test
    void findOtherCategoryProductsByMerchant_filtersCategorySourceCnpjAndExcludedItems() {
        var user = savedUser();
        var backfillable = product("Dipirona", ProductCategory.OTHER, CategorizationSource.NONE);
        var userLocked = product("Vitamina C", ProductCategory.OTHER, CategorizationSource.USER);
        var merchantLocked = product("Esmalte", ProductCategory.OTHER, CategorizationSource.MERCHANT);
        var alreadyCategorized = product("Arroz", ProductCategory.GROCERIES, CategorizationSource.DICTIONARY);
        var excludedPurchase = product("Sabonete", ProductCategory.OTHER, CategorizationSource.NONE);
        var elsewhereOnly = product("Shampoo", ProductCategory.OTHER, CategorizationSource.NONE);

        // Two purchases of the backfillable product at the pharmacy — DISTINCT must collapse them.
        receiptRepository.save(receiptWithItem(user, CNPJ_PHARMACY, backfillable, false));
        receiptRepository.save(receiptWithItem(user, CNPJ_PHARMACY, backfillable, false));
        receiptRepository.save(receiptWithItem(user, CNPJ_PHARMACY, userLocked, false));
        receiptRepository.save(receiptWithItem(user, CNPJ_PHARMACY, merchantLocked, false));
        receiptRepository.save(receiptWithItem(user, CNPJ_PHARMACY, alreadyCategorized, false));
        receiptRepository.save(receiptWithItem(user, CNPJ_PHARMACY, excludedPurchase, true));
        receiptRepository.save(receiptWithItem(user, CNPJ_OTHER, elsewhereOnly, false));

        var results = productRepository.findOtherCategoryProductsByMerchant(CNPJ_PHARMACY);

        assertEquals(1, results.size(), "only the unlocked OTHER product bought at this CNPJ qualifies");
        assertEquals(backfillable.getId(), results.get(0).getId());
        assertTrue(productRepository.findOtherCategoryProductsByMerchant("00000000000000").isEmpty());
    }

    // ---------------------------------------------------------- helpers

    private Product metadataProduct(String name, String genericName, String brand, String packSize, String packUnit) {
        return Product.builder()
                .normalizedName(name).genericName(genericName).brand(brand)
                .genericNameNorm(DescriptionNormalizer.normalizeOrNull(genericName))
                .brandNorm(DescriptionNormalizer.normalizeOrNull(brand))
                .packSize(new BigDecimal(packSize)).packUnit(packUnit)
                .category(ProductCategory.GROCERIES).build();
    }

    private Product product(String name, ProductCategory category, CategorizationSource source) {
        return productRepository.save(Product.builder()
                .normalizedName(name).category(category).categorizationSource(source).build());
    }

    private User savedUser() {
        var household = householdRepository.save(Household.builder().inviteCode("PROD01").build());
        return userRepository.save(User.builder()
                .name("Tester").email("prodrepo@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private Receipt receiptWithItem(User user, String cnpj, Product product, boolean excluded) {
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente(cnpj).marketName("Mercado")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("10.00")).totalPrice(new BigDecimal("10.00"))
                .product(product).excluded(excluded)
                .build());
        return receipt;
    }
}
