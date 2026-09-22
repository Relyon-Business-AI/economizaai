package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the content-search predicate of {@link ReceiptSpecifications#forSearch}:
 * raw description, per-item friendlyDescription, product name, market name, and
 * the household's product rename (household_product_aliases) — scoped so one
 * household's rename never matches another household's receipts.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReceiptSpecificationsTest {

    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private HouseholdProductAliasRepository householdProductAliasRepository;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = saveUser("RCPTSP1", "spec-owner@test.com");
        product = productRepository.save(Product.builder()
                .ean("7891000100103").normalizedName("GRAO FINO TIPO 1 5KG")
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

    private Receipt saveReceiptWithItem(User owner, Product itemProduct, String rawDescription) {
        var receipt = Receipt.builder()
                .user(owner).household(owner.getHousehold())
                .chaveAcesso("43" + System.nanoTime()).uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190").marketName("Mercado")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0))
                .totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build();
        receipt.addItem(ReceiptItem.builder()
                .lineNumber(1).rawDescription(rawDescription)
                .quantity(BigDecimal.ONE).totalPrice(new BigDecimal("10.00"))
                .product(itemProduct).build());
        return receiptRepository.save(receipt);
    }

    private List<Receipt> search(User owner, String query) {
        return receiptRepository.findAll(ReceiptSpecifications.forSearch(
                owner.getHousehold().getId(), null, null, null, null, null, query, true, null));
    }

    @Test
    void forSearch_matchesHouseholdFriendlyName() {
        var receipt = saveReceiptWithItem(user, product, "GRAO FINO TIPO 1 5KG");
        householdProductAliasRepository.save(HouseholdProductAlias.builder()
                .household(user.getHousehold()).product(product)
                .friendlyName("Arroz do mercado").build());

        var found = search(user, "arroz");

        assertEquals(1, found.size());
        assertEquals(receipt.getId(), found.get(0).getId());
    }

    @Test
    void forSearch_anotherHouseholdsRenameDoesNotMatch() {
        saveReceiptWithItem(user, product, "GRAO FINO TIPO 1 5KG");
        var neighbor = saveUser("RCPTSP2", "spec-neighbor@test.com");
        householdProductAliasRepository.save(HouseholdProductAlias.builder()
                .household(neighbor.getHousehold()).product(product)
                .friendlyName("Arroz do vizinho").build());

        assertTrue(search(user, "arroz").isEmpty(),
                "a rename made by another household must not affect this household's search");
    }

    @Test
    void forSearch_stillMatchesRawDescriptionAndFriendlyDescription() {
        var byRaw = saveReceiptWithItem(user, product, "ARROZ BRANCO 5KG");
        var renamedItem = saveReceiptWithItem(user, product, "GRAO FINO TIPO 1 5KG");
        renamedItem.getItems().get(0).setFriendlyDescription("arroz japonês");
        receiptRepository.save(renamedItem);

        var found = search(user, "arroz");

        var foundIds = found.stream().map(Receipt::getId).toList();
        assertEquals(2, found.size());
        assertTrue(foundIds.contains(byRaw.getId()));
        assertTrue(foundIds.contains(renamedItem.getId()));
    }
}
