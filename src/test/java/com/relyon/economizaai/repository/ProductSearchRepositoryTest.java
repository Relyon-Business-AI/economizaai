package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductAlias;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The bounded product-search queries: bound LIKE pattern with '!' escape, SQL cap, household scope. */
@DataJpaTest
@ActiveProfiles("test")
class ProductSearchRepositoryTest {

    private static final PageRequest TOP_200 = PageRequest.of(0, 200);

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductAliasRepository productAliasRepository;
    @Autowired private ReceiptItemRepository receiptItemRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReceiptRepository receiptRepository;

    @Test
    void searchCatalogByNameOrEan_matchesNameAndGenericCaseInsensitively_andEanExactly() {
        var arroz = saveProduct("Arroz Tio Joao 5KG", "Arroz", "111");
        var farinha = saveProduct("Farinha de ARROZ", null, null);
        var refrigerante = saveProduct("COCA-COLA LATA 350ML", "Refrigerante", "222");
        saveProduct("Feijao Preto", "Feijao", "333");

        assertThat(ids(productRepository.searchCatalogByNameOrEan("arroz", "%arroz%", TOP_200)))
                .containsExactly(arroz.getId(), farinha.getId()); // ordered by name
        assertThat(ids(productRepository.searchCatalogByNameOrEan("refri", "%refri%", TOP_200)))
                .containsExactly(refrigerante.getId());
        assertThat(ids(productRepository.searchCatalogByNameOrEan("222", "%222%", TOP_200)))
                .containsExactly(refrigerante.getId());
    }

    @Test
    void searchCatalogByNameOrEan_capsTheResultInSql() {
        for (var index = 0; index < 5; index++) {
            saveProduct("Biscoito Sabor " + index, "Biscoito", null);
        }

        assertThat(productRepository.searchCatalogByNameOrEan("biscoito", "%biscoito%", PageRequest.of(0, 3)))
                .hasSize(3);
    }

    @Test
    void searchCatalogByNameOrEan_escapedWildcardIsLiteral() {
        var discount = saveProduct("Sabao 50% OFF", null, null);
        saveProduct("Sabao 500G", null, null);

        // '!%' is a literal percent sign, not "anything"
        assertThat(ids(productRepository.searchCatalogByNameOrEan("50%", "%50!%%", TOP_200)))
                .containsExactly(discount.getId());
    }

    @Test
    void searchCatalogByAliasDescription_findsTheProductBehindAnAbsorbedDescription() {
        var survivor = saveProduct("COCA-COLA S/ACUCAR LATA 350ML", "Refrigerante", null);
        productAliasRepository.save(ProductAlias.builder()
                .product(survivor)
                .rawDescription("COCA COLA ZERO 350ML")
                .normalizedDescription("coca cola zero 350ml").build());
        saveProduct("Guarana Lata", "Refrigerante", null);

        assertThat(ids(productRepository.searchCatalogByAliasDescription("%cola zero%", TOP_200)))
                .containsExactly(survivor.getId());
    }

    @Test
    void searchPurchasedByHousehold_onlyReturnsWhatThatHouseholdConfirmed() {
        var owner = saveUser("OWN001", "search-owner@economizaai.app");
        var neighbour = saveUser("NEI001", "search-neighbour@economizaai.app");
        var bought = saveProduct("Arroz Comprado", "Arroz", null);
        var pendingOnly = saveProduct("Arroz Pendente", "Arroz", null);
        var neighboursOnly = saveProduct("Arroz do Vizinho", "Arroz", null);
        saveReceipt(owner, ReceiptStatus.CONFIRMED, bought);
        saveReceipt(owner, ReceiptStatus.PENDING_CONFIRMATION, pendingOnly);
        saveReceipt(neighbour, ReceiptStatus.CONFIRMED, neighboursOnly);

        var found = productRepository.searchPurchasedByHousehold(
                owner.getHousehold().getId(), "arroz", "%arroz%", TOP_200);

        assertThat(ids(found)).containsExactly(bought.getId());
    }

    @Test
    void findMostPurchasedProductIdsForHousehold_ordersByNumberOfNotasAndPagesInSql() {
        var owner = saveUser("OWN002", "search-most@economizaai.app");
        var neighbour = saveUser("NEI002", "search-most-neighbour@economizaai.app");
        var often = saveProduct("Leite", "Leite", null);
        var once = saveProduct("Cafe", "Cafe", null);
        var neighboursOnly = saveProduct("Cha", "Cha", null);
        saveReceipt(owner, ReceiptStatus.CONFIRMED, often, once);
        saveReceipt(owner, ReceiptStatus.CONFIRMED, often);
        saveReceipt(neighbour, ReceiptStatus.CONFIRMED, neighboursOnly);
        var householdId = owner.getHousehold().getId();

        var firstPage = receiptItemRepository.findMostPurchasedProductIdsForHousehold(householdId, PageRequest.of(0, 1));
        var secondPage = receiptItemRepository.findMostPurchasedProductIdsForHousehold(householdId, PageRequest.of(1, 1));

        assertThat(firstPage.getContent()).containsExactly(often.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(secondPage.getContent()).containsExactly(once.getId());
    }

    private Product saveProduct(String name, String genericName, String ean) {
        return productRepository.save(Product.builder()
                .ean(ean).normalizedName(name).genericName(genericName)
                .category(ProductCategory.GROCERIES).build());
    }

    private User saveUser(String inviteCode, String email) {
        var household = householdRepository.save(Household.builder().inviteCode(inviteCode).build());
        return userRepository.save(User.builder()
                .name("Tester").email(email).password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void saveReceipt(User user, ReceiptStatus status, Product... products) {
        var receipt = Receipt.builder()
                .user(user).household(user.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado A")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0)).totalAmount(BigDecimal.TEN)
                .qrPayload("payload").status(status)
                .build();
        var lineNumber = 1;
        for (var product : products) {
            receipt.addItem(ReceiptItem.builder()
                    .lineNumber(lineNumber++).rawDescription(product.getNormalizedName())
                    .quantity(BigDecimal.ONE).unitPrice(BigDecimal.ONE).totalPrice(BigDecimal.ONE)
                    .product(product).build());
        }
        receiptRepository.saveAndFlush(receipt);
    }

    private static List<UUID> ids(List<Product> products) {
        return products.stream().map(Product::getId).toList();
    }
}
